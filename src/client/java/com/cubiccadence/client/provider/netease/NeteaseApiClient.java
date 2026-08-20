package com.cubiccadence.client.provider.netease;

import com.cubiccadence.auth.AuthSession;
import com.cubiccadence.model.Artist;
import com.cubiccadence.model.Availability;
import com.cubiccadence.model.MembershipTier;
import com.cubiccadence.model.PlaybackAccess;
import com.cubiccadence.model.PlaybackSource;
import com.cubiccadence.model.PlaylistOwnership;
import com.cubiccadence.model.PlaylistSummary;
import com.cubiccadence.model.Track;
import com.cubiccadence.model.UserProfile;
import com.cubiccadence.provider.PageRequest;
import com.cubiccadence.provider.AudioQuality;
import com.cubiccadence.provider.PlaylistPage;
import com.cubiccadence.provider.PlaylistSummaryPage;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Maps authenticated api-enhanced responses into provider-neutral records. */
public final class NeteaseApiClient {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_PAGE_SIZE = 50;

    private final URI baseUri;
    private final HttpClient httpClient;

    public NeteaseApiClient(URI baseUri) {
        this(baseUri, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    NeteaseApiClient(URI baseUri, HttpClient httpClient) {
        this.baseUri = validateBaseUri(baseUri);
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    public CompletableFuture<UserProfile> getCurrentUser(AuthSession session) {
        validateSession(session);
        return getJson("/user/account", Map.of("cookie", session.cookie()))
                .thenCompose(account -> {
                    UserProfile baseProfile = parseUserProfile(account);
                    Map<String, String> authenticatedQuery = authenticatedQuery(session);
                    CompletableFuture<JsonObject> level = getJson("/user/level", authenticatedQuery)
                            .thenApply(body -> {
                                requireSuccess(body);
                                return body;
                            })
                            .exceptionally(ignored -> null);
                    Map<String, String> vipQuery = new LinkedHashMap<>(authenticatedQuery);
                    vipQuery.put("uid", baseProfile.userId());
                    CompletableFuture<JsonObject> vip = getJson("/vip/info", vipQuery)
                            .thenApply(body -> {
                                requireSuccess(body);
                                return body;
                            })
                            .exceptionally(ignored -> null);
                    return level.thenCombine(
                            vip,
                            (levelBody, vipBody) -> enrichUserProfile(baseProfile, levelBody, vipBody)
                    );
                });
    }

    public CompletableFuture<PlaylistSummaryPage> getUserPlaylists(
            AuthSession session,
            String userId,
            PageRequest pageRequest
    ) {
        validateSession(session);
        Objects.requireNonNull(pageRequest, "pageRequest");
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (pageRequest.page() < 0 || pageRequest.pageSize() < 1 || pageRequest.pageSize() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page request is outside the supported range");
        }
        long offset = (long) pageRequest.page() * pageRequest.pageSize();
        Map<String, String> query = new LinkedHashMap<>();
        query.put("uid", userId);
        query.put("limit", Integer.toString(pageRequest.pageSize()));
        query.put("offset", Long.toString(offset));
        query.put("cookie", session.cookie());
        return getJson("/user/playlist", query)
                .thenApply(body -> parseUserPlaylists(body, userId, pageRequest.pageSize()));
    }

    public CompletableFuture<PlaylistPage> getPlaylistTracks(
            AuthSession session,
            String playlistId,
            PageRequest pageRequest
    ) {
        validateSession(session);
        Objects.requireNonNull(pageRequest, "pageRequest");
        if (playlistId == null || playlistId.isBlank()) {
            throw new IllegalArgumentException("playlistId must not be blank");
        }
        if (pageRequest.page() < 0 || pageRequest.pageSize() < 1 || pageRequest.pageSize() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page request is outside the supported range");
        }
        long offset = (long) pageRequest.page() * pageRequest.pageSize();
        Map<String, String> query = new LinkedHashMap<>();
        query.put("id", playlistId);
        query.put("limit", Integer.toString(pageRequest.pageSize()));
        query.put("offset", Long.toString(offset));
        query.put("cookie", session.cookie());
        return getJson("/playlist/track/all", query)
                .thenApply(body -> parsePlaylistTracks(body, pageRequest.page(), pageRequest.pageSize()));
    }

    public CompletableFuture<PlaybackSource> resolvePlaybackSource(
            AuthSession session,
            String trackId,
            AudioQuality quality
    ) {
        validateSession(session);
        if (trackId == null || trackId.isBlank()) {
            throw new IllegalArgumentException("trackId must not be blank");
        }
        Objects.requireNonNull(quality, "quality");
        if (quality == AudioQuality.LOSSLESS) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("lossless streaming is not supported by the current decoder")
            );
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("id", trackId);
        query.put("level", requestLevel(quality));
        query.put("cookie", session.cookie());
        return getJson("/song/url/v1", query)
                .thenApply(body -> parsePlaybackSource(body, quality));
    }

    static UserProfile parseUserProfile(JsonObject body) {
        requireSuccess(body);
        JsonObject profile = requiredObject(body, "profile");
        String userId = requiredString(profile, "userId");
        String nickname = requiredString(profile, "nickname");
        String avatarUrl = optionalString(profile, "avatarUrl");
        int level = optionalInt(body, "level", UserProfile.UNKNOWN_LEVEL);
        Integer vipType = optionalNullableInt(profile, "vipType");
        return new UserProfile(
                NeteaseMusicProvider.PROVIDER_ID,
                userId,
                nickname,
                avatarUrl,
                level < 0 ? UserProfile.UNKNOWN_LEVEL : level,
                vipType == null ? MembershipTier.UNKNOWN : membershipTier(vipType)
        );
    }

    static UserProfile enrichUserProfile(UserProfile profile, JsonObject levelBody, JsonObject vipBody) {
        int level = profile.level();
        if (levelBody != null) {
            requireSuccess(levelBody);
            JsonObject data = optionalObject(levelBody, "data");
            Integer enrichedLevel = data == null
                    ? optionalNullableInt(levelBody, "level")
                    : optionalNullableInt(data, "level");
            if (enrichedLevel != null && enrichedLevel >= 0) {
                level = enrichedLevel;
            }
        }

        MembershipTier membershipTier = vipBody == null
                ? failedMembershipFallback(profile.membershipTier())
                : parseVipMembership(vipBody);
        return new UserProfile(
                profile.providerId(),
                profile.userId(),
                profile.displayName(),
                profile.avatarUrl(),
                level,
                membershipTier
        );
    }

    static PlaylistSummaryPage parseUserPlaylists(JsonObject body, String userId, int requestedPageSize) {
        requireSuccess(body);
        JsonElement playlistsElement = body.get("playlist");
        if (playlistsElement == null || !playlistsElement.isJsonArray()) {
            throw new ApiException("api-enhanced returned no playlist array");
        }
        JsonArray playlists = playlistsElement.getAsJsonArray();
        List<PlaylistSummary> items = new ArrayList<>(playlists.size());
        for (JsonElement element : playlists) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject playlist = element.getAsJsonObject();
            String creatorId = optionalNestedString(playlist, "creator", "userId");
            PlaylistOwnership ownership = optionalInt(playlist, "specialType", 0) == 5
                    ? PlaylistOwnership.SPECIAL
                    : userId.equals(creatorId) ? PlaylistOwnership.CREATED : PlaylistOwnership.COLLECTED;
            items.add(new PlaylistSummary(
                    NeteaseMusicProvider.PROVIDER_ID,
                    requiredString(playlist, "id"),
                    requiredString(playlist, "name"),
                    optionalString(playlist, "coverImgUrl"),
                    Math.max(0, optionalInt(playlist, "trackCount", 0)),
                    ownership
            ));
        }
        boolean hasNext = optionalBoolean(body, "more", items.size() >= requestedPageSize);
        Integer total = optionalNullableInt(body, "playlistCount");
        return new PlaylistSummaryPage(items, hasNext, total);
    }

    static PlaylistPage parsePlaylistTracks(JsonObject body, int page, int requestedPageSize) {
        requireSuccess(body);
        JsonElement songsElement = body.get("songs");
        if (songsElement == null || !songsElement.isJsonArray()) {
            throw new ApiException("api-enhanced returned no songs array");
        }

        Map<String, JsonObject> privileges = new HashMap<>();
        JsonElement privilegesElement = body.get("privileges");
        if (privilegesElement != null && privilegesElement.isJsonArray()) {
            for (JsonElement element : privilegesElement.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    JsonObject privilege = element.getAsJsonObject();
                    String trackId = optionalString(privilege, "id");
                    if (!trackId.isBlank()) {
                        privileges.put(trackId, privilege);
                    }
                }
            }
        }

        JsonArray songs = songsElement.getAsJsonArray();
        List<Track> tracks = new ArrayList<>(songs.size());
        for (JsonElement element : songs) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject song = element.getAsJsonObject();
            String trackId = requiredString(song, "id");
            JsonObject album = optionalObject(song, "al");
            JsonObject privilege = privileges.get(trackId);
            if (privilege == null) {
                privilege = optionalObject(song, "privilege");
            }
            tracks.add(new Track(
                    NeteaseMusicProvider.PROVIDER_ID,
                    trackId,
                    requiredString(song, "name"),
                    parseArtists(song),
                    album == null ? "" : optionalString(album, "name"),
                    album == null ? "" : optionalString(album, "picUrl"),
                    Math.max(0L, optionalLong(song, "dt", 0L)),
                    availability(song, privilege)
            ));
        }
        boolean hasNext = tracks.size() >= requestedPageSize;
        String nextCursor = hasNext ? Integer.toString(page + 1) : null;
        return new PlaylistPage(tracks, hasNext, nextCursor);
    }

    static PlaybackSource parsePlaybackSource(JsonObject body, AudioQuality requestedQuality) {
        requireSuccess(body);
        JsonElement dataElement = body.get("data");
        if (dataElement == null || !dataElement.isJsonArray() || dataElement.getAsJsonArray().isEmpty()) {
            throw new ApiException("api-enhanced returned no playback source");
        }
        JsonElement first = dataElement.getAsJsonArray().get(0);
        if (!first.isJsonObject()) {
            throw new ApiException("api-enhanced returned an invalid playback source");
        }
        JsonObject data = first.getAsJsonObject();
        String sourceUrl = optionalString(data, "url");
        if (sourceUrl.isBlank()) {
            throw new ApiException("track is unavailable for the current account and region");
        }
        URI uri = safePlaybackUri(sourceUrl);
        String type = optionalString(data, "type").toLowerCase();
        if (!type.isBlank() && !"mp3".equals(type) && !"mpeg".equals(type)) {
            throw new ApiException("playback source format is not supported: " + type);
        }
        String level = optionalString(data, "level");
        AudioQuality actualQuality = level.isBlank() ? requestedQuality : responseQuality(level);
        Integer bitrate = optionalNullableInt(data, "br");
        long expiresInSeconds = Math.max(0L, optionalLong(data, "expi", 0L));
        long expiresAt = expiresInSeconds == 0L
                ? 0L
                : System.currentTimeMillis() + Math.max(1L, expiresInSeconds - 5L) * 1000L;
        JsonObject trial = optionalObject(data, "freeTrialInfo");
        long playableDuration = Math.max(0L, optionalLong(data, "time", 0L));
        if (trial != null) {
            long start = Math.max(0L, optionalLong(trial, "start", 0L));
            long end = Math.max(start, optionalLong(trial, "end", start));
            if (end > start) {
                playableDuration = end - start;
            }
        }
        return new PlaybackSource(
                uri,
                "audio/mpeg",
                expiresAt,
                Map.of(),
                actualQuality,
                bitrate,
                trial == null ? PlaybackAccess.FULL : PlaybackAccess.TRIAL,
                playableDuration
        );
    }

    private static List<Artist> parseArtists(JsonObject song) {
        JsonElement artistsElement = song.get("ar");
        if (artistsElement == null || !artistsElement.isJsonArray()) {
            return List.of();
        }
        List<Artist> artists = new ArrayList<>();
        for (JsonElement element : artistsElement.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject artist = element.getAsJsonObject();
            String artistId = optionalString(artist, "id");
            String name = optionalString(artist, "name");
            if (!artistId.isBlank() && !name.isBlank()) {
                artists.add(new Artist(NeteaseMusicProvider.PROVIDER_ID, artistId, name));
            }
        }
        return List.copyOf(artists);
    }

    private static Availability availability(JsonObject song, JsonObject privilege) {
        if (privilege == null) {
            return Availability.UNKNOWN;
        }
        if (optionalBoolean(privilege, "toast", false)) {
            return Availability.REGION_RESTRICTED;
        }
        if (optionalInt(privilege, "st", 0) < 0) {
            return Availability.COPYRIGHT_RESTRICTED;
        }
        if (hasPlaybackRight(privilege)) {
            return Availability.PLAYABLE;
        }
        int fee = optionalInt(song, "fee", -1);
        if (fee == 1 || fee == 8) {
            return Availability.MEMBERSHIP_REQUIRED;
        }
        if (fee == 4) {
            return Availability.COPYRIGHT_RESTRICTED;
        }
        if (privilege.has("pl") || privilege.has("plLevel")) {
            return Availability.UNAVAILABLE;
        }
        return Availability.UNKNOWN;
    }

    private static boolean hasPlaybackRight(JsonObject privilege) {
        if (optionalInt(privilege, "pl", 0) > 0) {
            return true;
        }
        String level = optionalString(privilege, "plLevel");
        return !level.isBlank() && !"none".equalsIgnoreCase(level);
    }

    private static String requestLevel(AudioQuality quality) {
        return switch (quality) {
            case LOW -> "standard";
            case STANDARD -> "higher";
            case HIGH -> "exhigh";
            case LOSSLESS -> "lossless";
        };
    }

    private static AudioQuality responseQuality(String level) {
        return switch (level.toLowerCase()) {
            case "standard" -> AudioQuality.LOW;
            case "higher" -> AudioQuality.STANDARD;
            case "exhigh" -> AudioQuality.HIGH;
            case "lossless", "hires", "jyeffect", "sky", "vivid", "dolby", "jymaster" ->
                    AudioQuality.LOSSLESS;
            default -> AudioQuality.STANDARD;
        };
    }

    private static URI safePlaybackUri(String value) {
        try {
            URI parsed = URI.create(value);
            if (parsed.getHost() == null || parsed.getUserInfo() != null
                    || parsed.getFragment() != null
                    || !("https".equalsIgnoreCase(parsed.getScheme())
                    || "http".equalsIgnoreCase(parsed.getScheme()))) {
                throw new ApiException("api-enhanced returned an unsafe playback source");
            }
            if ("http".equalsIgnoreCase(parsed.getScheme())) {
                return new URI(
                        "https",
                        null,
                        parsed.getHost(),
                        parsed.getPort(),
                        parsed.getPath(),
                        parsed.getQuery(),
                        null
                );
            }
            return parsed;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException("api-enhanced returned an invalid playback source", exception);
        }
    }

    private CompletableFuture<JsonObject> getJson(String path, Map<String, String> query) {
        HttpRequest request = HttpRequest.newBuilder(resolve(path, query))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "Cubic-Cadence/1.0")
                .GET()
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    byte[] body = response.body();
                    if (body.length > MAX_RESPONSE_BYTES) {
                        throw new ApiException("api-enhanced response was too large");
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new ApiException("api-enhanced returned HTTP " + response.statusCode());
                    }
                    try {
                        JsonElement parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
                        if (!parsed.isJsonObject()) {
                            throw new ApiException("api-enhanced returned a non-object response");
                        }
                        return parsed.getAsJsonObject();
                    } catch (RuntimeException exception) {
                        if (exception instanceof ApiException apiException) {
                            throw apiException;
                        }
                        throw new ApiException("api-enhanced returned invalid JSON", exception);
                    }
                });
    }

    private static Map<String, String> authenticatedQuery(AuthSession session) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("cookie", session.cookie());
        query.put("timestamp", Long.toString(System.currentTimeMillis()));
        return query;
    }

    private URI resolve(String path, Map<String, String> query) {
        String base = baseUri.toString();
        StringBuilder url = new StringBuilder(base.endsWith("/")
                ? base.substring(0, base.length() - 1) : base);
        url.append(path);
        if (!query.isEmpty()) {
            url.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (!first) {
                    url.append('&');
                }
                first = false;
                url.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
        }
        return URI.create(url.toString());
    }

    private static void validateSession(AuthSession session) {
        if (session == null || !NeteaseMusicProvider.PROVIDER_ID.equals(session.providerId())
                || session.cookie() == null || session.cookie().isBlank()) {
            throw new IllegalArgumentException("a valid NetEase session is required");
        }
    }

    private static void requireSuccess(JsonObject body) {
        JsonElement code = body.get("code");
        if (code != null && !code.isJsonNull()) {
            try {
                if (code.getAsInt() != 200) {
                    throw new ApiException("api-enhanced rejected the request");
                }
            } catch (NumberFormatException | UnsupportedOperationException exception) {
                throw new ApiException("api-enhanced returned an invalid status code", exception);
            }
        }
    }

    private static JsonObject requiredObject(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        if (element == null || !element.isJsonObject()) {
            throw new ApiException("api-enhanced response is missing " + name);
        }
        return element.getAsJsonObject();
    }

    private static JsonObject optionalObject(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String requiredString(JsonObject object, String name) {
        String value = optionalString(object, name);
        if (value.isBlank()) {
            throw new ApiException("api-enhanced response is missing " + name);
        }
        return value;
    }

    private static String optionalString(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return "";
        }
        try {
            return element.getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String optionalNestedString(JsonObject object, String objectName, String fieldName) {
        JsonElement nested = object.get(objectName);
        return nested != null && nested.isJsonObject()
                ? optionalString(nested.getAsJsonObject(), fieldName)
                : "";
    }

    private static int optionalInt(JsonObject object, String name, int defaultValue) {
        Integer value = optionalNullableInt(object, name);
        return value == null ? defaultValue : value;
    }

    private static Integer optionalNullableInt(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean optionalBoolean(JsonObject object, String name, boolean defaultValue) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return defaultValue;
        }
        try {
            return element.getAsBoolean();
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }

    private static MembershipTier membershipTier(int vipType) {
        return switch (vipType) {
            case 10 -> MembershipTier.MUSIC_PACKAGE;
            case 11 -> MembershipTier.BLACK_VINYL_VIP;
            default -> MembershipTier.NON_MEMBER;
        };
    }

    private static MembershipTier failedMembershipFallback(MembershipTier accountMembership) {
        return accountMembership == MembershipTier.MUSIC_PACKAGE
                || accountMembership == MembershipTier.BLACK_VINYL_VIP
                ? accountMembership
                : MembershipTier.UNKNOWN;
    }

    private static MembershipTier parseVipMembership(JsonObject body) {
        requireSuccess(body);
        JsonObject data = optionalObject(body, "data");
        if (data == null) {
            return MembershipTier.UNKNOWN;
        }
        JsonObject associator = optionalObject(data, "associator");
        JsonObject musicPackage = optionalObject(data, "musicPackage");
        if (hasActiveRights(associator) || optionalInt(data, "redVipLevel", 0) > 0) {
            return MembershipTier.BLACK_VINYL_VIP;
        }
        if (hasActiveRights(musicPackage)) {
            return MembershipTier.MUSIC_PACKAGE;
        }
        return MembershipTier.NON_MEMBER;
    }

    private static boolean hasActiveRights(JsonObject rights) {
        if (rights == null) {
            return false;
        }
        if (optionalBoolean(rights, "rights", false)) {
            return true;
        }
        return optionalInt(rights, "vipCode", 0) > 0
                && optionalLong(rights, "expireTime", 0L) > System.currentTimeMillis();
    }

    private static long optionalLong(JsonObject object, String name, long defaultValue) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return defaultValue;
        }
        try {
            return element.getAsLong();
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }

    private static URI validateBaseUri(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("api-enhanced base URL is invalid");
        }
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean loopbackHttp = "http".equalsIgnoreCase(uri.getScheme())
                && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
        if (!https && !loopbackHttp) {
            throw new IllegalArgumentException("api-enhanced base URL must use HTTPS (HTTP is local-only)");
        }
        return uri;
    }

    static final class ApiException extends RuntimeException {
        ApiException(String message) {
            super(message);
        }

        ApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
