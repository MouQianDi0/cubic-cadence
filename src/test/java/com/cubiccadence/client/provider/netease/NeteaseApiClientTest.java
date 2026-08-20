package com.cubiccadence.client.provider.netease;

import com.cubiccadence.model.MembershipTier;
import com.cubiccadence.model.PlaylistOwnership;
import com.cubiccadence.model.UserProfile;
import com.cubiccadence.provider.PlaylistSummaryPage;
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
    void rejectsAccountResponseWithoutProfile() {
        assertThrows(
                NeteaseApiClient.ApiException.class,
                () -> NeteaseApiClient.parseUserProfile(JsonParser.parseString("{\"code\":200}").getAsJsonObject())
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
