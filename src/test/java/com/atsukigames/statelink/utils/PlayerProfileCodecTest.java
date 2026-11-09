package com.atsukigames.statelink.utils;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PlayerProfileCodecTest {
    private static final UUID ID = UUID.fromString("13cc32b9-f73b-43ed-8e75-a08cc679ed88");
    @Test void propertiesAndSignaturesRoundTripWithoutValueDedupe() {
        var profile = new GameProfile(ID, "ProfileTest");
        profile.getProperties().put("textures", new Property("textures", "full-value", "full-signature"));
        profile.getProperties().put("custom", new Property("same", "identical"));
        profile.getProperties().put("custom", new Property("same", "identical"));
        var decoded = PlayerProfileCodec.decode(PlayerProfileCodec.encode(profile), ID, "ProfileTest");
        assertEquals(3, decoded.size());
        var texture = decoded.get("textures").iterator().next();
        assertEquals("full-value", texture.getValue());
        assertEquals("full-signature", texture.getSignature());
    }
    @Test void changedSkinChangesCheckpointRepresentation() {
        var profile = new GameProfile(ID, "ProfileTest");
        var before = PlayerProfileCodec.encode(profile);
        profile.getProperties().put("textures", new Property("textures", "changed"));
        assertNotEquals(before, PlayerProfileCodec.encode(profile));
    }
    @Test void legacyIdentityPlaceholderOfARenamedPlayerKeepsLocalProfileInsteadOfRefusingLogin() {
        String legacy = "{\"name\":\"OldName\",\"uuid\":\"" + ID + "\"}";
        assertTrue(PlayerProfileCodec.isLegacyIdentityPlaceholder(legacy));
        assertNull(PlayerProfileCodec.decode(legacy, ID, "NewName"), "the stored 2.1.11 name is not an identity");
        assertNull(PlayerProfileCodec.decode(legacy, ID, "OldName"));
        // The UUID is the identity: a placeholder of another player is still refused.
        assertThrows(IllegalArgumentException.class, () -> PlayerProfileCodec.decode(legacy, UUID.randomUUID(), "OldName"));
    }
    @Test void realProfileDataIsNeverMistakenForTheLegacyPlaceholder() {
        String real = PlayerProfileCodec.encode(new GameProfile(ID, "ProfileTest"));
        assertFalse(PlayerProfileCodec.isLegacyIdentityPlaceholder(real));
        assertFalse(PlayerProfileCodec.isLegacyIdentityPlaceholder("{\"name\":\"a\",\"uuid\":\"b\",\"properties\":[]}"));
        assertFalse(PlayerProfileCodec.isLegacyIdentityPlaceholder("{\"name\":1,\"uuid\":\"b\"}"));
        assertFalse(PlayerProfileCodec.isLegacyIdentityPlaceholder(null));
        assertFalse(PlayerProfileCodec.isLegacyIdentityPlaceholder("not json"));
    }
    @Test void sameUuidWithChangedAccountNamePreservesCurrentLoginProperties() {
        var saved = new GameProfile(ID, "OldName");
        saved.getProperties().put("textures", new Property("textures", "old-value", "old-signature"));
        String json = PlayerProfileCodec.encode(saved);
        assertNull(PlayerProfileCodec.decode(json, ID, "NewName"));
        assertNull(PlayerProfileCodec.decode(json, ID, "oldname"));
        assertEquals(1, PlayerProfileCodec.decode(json, ID, "OldName").size());
    }
    @Test void uuidMismatchIsRejectedRegardlessOfAccountName() {
        var json = PlayerProfileCodec.encode(new GameProfile(ID, "ProfileTest"));
        assertThrows(IllegalArgumentException.class, () -> PlayerProfileCodec.decode(json, UUID.randomUUID(), "ProfileTest"));
        assertThrows(IllegalArgumentException.class, () -> PlayerProfileCodec.decode(json, UUID.randomUUID(), "OtherName"));
    }
    @Test void legacyIdentityOnlyRowPreservesLocalProperties() {
        assertNull(PlayerProfileCodec.decode("{\"uuid\":\""+ID+"\",\"name\":\"ProfileTest\"}", ID, "ProfileTest"));
    }
    @Test void invalidFormatFailsWholeProfile() {
        var json = PlayerProfileCodec.encode(new GameProfile(ID, "ProfileTest")).replace("\"format\":1", "\"format\":1.5");
        assertThrows(ArithmeticException.class, () -> PlayerProfileCodec.decode(json, ID, "ProfileTest"));
        assertThrows(ArithmeticException.class, () -> PlayerProfileCodec.decode(json, ID, "Renamed"));
    }
}
