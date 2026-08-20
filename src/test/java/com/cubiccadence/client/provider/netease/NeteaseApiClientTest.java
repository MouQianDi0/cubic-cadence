package com.cubiccadence.client.provider.netease;

import com.cubiccadence.model.MembershipTier;
import com.cubiccadence.model.Availability;
import com.cubiccadence.model.PlaylistOwnership;
import com.cubiccadence.model.PlaybackAccess;
import com.cubiccadence.model.PlaybackSource;
import com.cubiccadence.model.UserProfile;
import com.cubiccadence.provider.AudioQuality;
import com.cubiccadence.provider.PlaylistSummaryPage;
import com.cubiccadence.provider.PlaylistPage;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeteaseApiClientTest {
    @Test
    void mapsAccountProfileLevelAndBlackVinylMembership() {
        UserProfile profile = NeteaseApiClient.parseUserProfile(JsonParser.parseString("""
                {
                  "code": 200,
                  "level": 8,
                  "profile": {
                    "userId": 123456789,
                    "nickname": "方律测试用户",
                    "avatarUrl": "https://p1.music.126.net/avatar.jpg",
                    "vipType": 11
                  }
                }
                """).getAsJsonObject());

        assertEquals("123456789", profile.userId());
        assertEquals("方律测试用户", profile.displayName());
        assertEquals(8, profile.level());
        assertEquals(MembershipTier.BLACK_VINYL_VIP, profile.membershipTier());
    }

    @Test
    void mapsMusicPackageAndUnknownVipTypesSafely() {
        UserProfile musicPackage = NeteaseApiClient.parseUserProfile(accountJson(10));
        UserProfile unknown = NeteaseApiClient.parseUserProfile(accountJson(99));

        assertEquals(MembershipTier.MUSIC_PACKAGE, musicPackage.membershipTier());
        assertEquals(MembershipTier.NON_MEMBER, unknown.membershipTier());
    }

    @Test
    void enrichesLevelAndBlackVinylMembership() {
        UserProfile enriched = NeteaseApiClient.enrichUserProfile(
                NeteaseApiClient.parseUserProfile(accountJson(0)),
                JsonParser.parseString("{\"code\":200,\"data\":{\"level\":8}}").getAsJsonObject(),
                JsonParser.parseString("{\"code\":200,\"data\":{\"redVipLevel\":1}}").getAsJsonObject()
        );

        assertEquals(8, enriched.level());
        assertEquals(MembershipTier.BLACK_VINYL_VIP, enriched.membershipTier());
    }

    @Test
    void distinguishesNoMembershipFromUnavailableMembershipDetails() {
        UserProfile base = NeteaseApiClient.parseUserProfile(accountJson(0));
        UserProfile noMembership = NeteaseApiClient.enrichUserProfile(
                base,
                null,
                JsonParser.parseString("{\"code\":200,\"data\":{\"redVipLevel\":0}}").getAsJsonObject()
        );
        UserProfile unavailable = NeteaseApiClient.enrichUserProfile(base, null, null);

        assertEquals(MembershipTier.NON_MEMBER, noMembership.membershipTier());
        assertEquals(MembershipTier.UNKNOWN, unavailable.membershipTier());
    }

    @Test
    void preservesMusicPackageWhenVipDetailIsUnavailable() {
        UserProfile enriched = NeteaseApiClient.enrichUserProfile(
                NeteaseApiClient.parseUserProfile(accountJson(10)),
                null,
                null
        );

        assertEquals(MembershipTier.MUSIC_PACKAGE, enriched.membershipTier());
    }

    @Test
    void mapsEightItemPageMetadataAndOwnership() {
        PlaylistSummaryPage page = NeteaseApiClient.parseUserPlaylists(JsonParser.parseString("""
                {
                  "code": 200,
                  "more": true,
                  "playlistCount": 9,
                  "playlist": [
                    {
                      "id": 1001,
                      "name": "我创建的歌单",
                      "coverImgUrl": "https://p1.music.126.net/created.jpg",
                      "trackCount": 12,
                      "specialType": 5,
                      "creator": {"userId": 123456789}
                    },
                    {
                      "id": 1002,
                      "name": "我收藏的歌单",
                      "coverImgUrl": "https://p1.music.126.net/collected.jpg",
                      "trackCount": 34,
                      "creator": {"userId": 987654321}
                    }
                  ]
                }
                """).getAsJsonObject(), "123456789", 8);

        assertEquals(2, page.items().size());
        assertTrue(page.hasNext());
        assertEquals(9, page.total());
        assertEquals(PlaylistOwnership.SPECIAL, page.items().get(0).ownership());
        assertEquals(PlaylistOwnership.COLLECTED, page.items().get(1).ownership());
    }

    @Test
    void mapsTrackMetadataAndConservativeAvailabilityStates() {
        PlaylistPage page = NeteaseApiClient.parsePlaylistTracks(JsonParser.parseString("""
                {
                  "code": 200,
                  "songs": [
                    {"id":1,"name":"可播放","fee":1,"dt":185000,
                     "ar":[{"id":11,"name":"歌手甲"}],
                     "al":{"name":"专辑甲","picUrl":"https://p1.music.126.net/1.jpg"}},
                    {"id":2,"name":"会员歌曲","fee":1,"dt":200000,"ar":[],"al":{"name":"","picUrl":""}},
                    {"id":3,"name":"版权歌曲","fee":0,"dt":0,"ar":[],"al":{"name":"","picUrl":""}},
                    {"id":4,"name":"地区歌曲","fee":0,"dt":0,"ar":[],"al":{"name":"","picUrl":""}},
                    {"id":5,"name":"不可播放","fee":0,"dt":0,"ar":[],"al":{"name":"","picUrl":""}},
                    {"id":6,"name":"未知歌曲","fee":0,"dt":0,"ar":[],"al":{"name":"","picUrl":""}}
                  ],
                  "privileges": [
                    {"id":1,"st":0,"toast":false,"pl":320000},
                    {"id":2,"st":0,"toast":false,"pl":0},
                    {"id":3,"st":-200,"toast":false,"pl":0},
                    {"id":4,"st":0,"toast":true,"pl":0},
                    {"id":5,"st":0,"toast":false,"pl":0},
                    {"id":6,"st":0,"toast":false}
                  ]
                }
                """).getAsJsonObject(), 0, 50);

        assertEquals(6, page.tracks().size());
        assertEquals("可播放", page.tracks().get(0).title());
        assertEquals("歌手甲", page.tracks().get(0).artists().getFirst().name());
        assertEquals("专辑甲", page.tracks().get(0).albumName());
        assertEquals("https://p1.music.126.net/1.jpg", page.tracks().get(0).coverUrl());
        assertEquals(185000L, page.tracks().get(0).durationMs());
        assertEquals(Availability.PLAYABLE, page.tracks().get(0).availability());
        assertEquals(Availability.MEMBERSHIP_REQUIRED, page.tracks().get(1).availability());
        assertEquals(Availability.COPYRIGHT_RESTRICTED, page.tracks().get(2).availability());
        assertEquals(Availability.REGION_RESTRICTED, page.tracks().get(3).availability());
        assertEquals(Availability.UNAVAILABLE, page.tracks().get(4).availability());
        assertEquals(Availability.UNKNOWN, page.tracks().get(5).availability());
        assertTrue(!page.hasNext());
    }

    @Test
    void rejectsAccountResponseWithoutProfile() {
        assertThrows(
                NeteaseApiClient.ApiException.class,
                () -> NeteaseApiClient.parseUserProfile(JsonParser.parseString("{\"code\":200}").getAsJsonObject())
        );
    }

    @Test
    void mapsExpiringHttpsPlaybackSourceWithoutPersistedCredentials() {
        long before = System.currentTimeMillis();
        PlaybackSource source = NeteaseApiClient.parsePlaybackSource(JsonParser.parseString("""
                {
                  "code": 200,
                  "data": [{
                    "id": 1,
                    "url": "http://m801.music.126.net/song.mp3?token=secret",
                    "type": "mp3",
                    "level": "exhigh",
                    "br": 320000,
                    "expi": 1200,
                    "time": 185000,
                    "freeTrialInfo": null
                  }]
                }
                """).getAsJsonObject(), AudioQuality.HIGH);

        assertEquals("https", source.uri().getScheme());
        assertEquals("audio/mpeg", source.contentType());
        assertEquals(AudioQuality.HIGH, source.quality());
        assertEquals(320000, source.bitrate());
        assertEquals(PlaybackAccess.FULL, source.access());
        assertEquals(185000L, source.playableDurationMs());
        assertTrue(source.requestHeaders().isEmpty());
        assertTrue(source.expiresAtEpochMs() > before);
    }

    @Test
    void labelsPlatformProvidedTrialAndRejectsMissingUrl() {
        PlaybackSource source = NeteaseApiClient.parsePlaybackSource(JsonParser.parseString("""
                {"code":200,"data":[{
                  "url":"https://m801.music.126.net/trial.mp3",
                  "type":"mp3","level":"higher","time":180000,
                  "freeTrialInfo":{"start":30000,"end":60000}
                }]}
                """).getAsJsonObject(), AudioQuality.STANDARD);

        assertEquals(PlaybackAccess.TRIAL, source.access());
        assertEquals(30000L, source.playableDurationMs());
        assertThrows(
                NeteaseApiClient.ApiException.class,
                () -> NeteaseApiClient.parsePlaybackSource(
                        JsonParser.parseString("{\"code\":200,\"data\":[{\"url\":null}]} ").getAsJsonObject(),
                        AudioQuality.STANDARD
                )
        );
    }

    private static com.google.gson.JsonObject accountJson(int vipType) {
        return JsonParser.parseString("""
                {
                  "code": 200,
                  "level": 1,
                  "profile": {
                    "userId": 1,
                    "nickname": "test",
                    "avatarUrl": "",
                    "vipType": %d
                  }
                }
                """.formatted(vipType)).getAsJsonObject();
    }
}
