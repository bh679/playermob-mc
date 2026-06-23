package games.brennan.playermob.skin;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Sanity-checks the default skin pack shipped at
 * {@code data/playermob/playermob_skins/*.json}: every file parses through
 * {@link PlayerMobSkin#CODEC} and points at a {@code textures.minecraft.net}
 * URL. Guards against typo'd or broken-format JSON sneaking into a release.
 */
class DefaultSkinPackTest {

    private static final String SKINS_RESOURCE_PATH = "/data/playermob/playermob_skins";
    private static final String EXPECTED_URL_PREFIX = "http://textures.minecraft.net/texture/";
    private static final int MIN_EXPECTED_SKINS = 60;

    @Test
    void defaultSkinPackParses() throws Exception {
        Path skinsDir = resolveSkinsDir();
        int parsed = 0;
        try (Stream<Path> files = Files.list(skinsDir)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".json"))::iterator) {
                String body = Files.readString(file, StandardCharsets.UTF_8);
                var result = PlayerMobSkin.CODEC.parse(
                    JsonOps.INSTANCE, JsonParser.parseString(body));
                if (result.error().isPresent()) {
                    fail("Failed to parse " + file.getFileName() + ": "
                         + result.error().get().message());
                }
                PlayerMobSkin skin = result.result().orElseThrow();
                assertTrue(skin.textureUrl().startsWith(EXPECTED_URL_PREFIX),
                    file.getFileName() + ": texture URL must start with "
                    + EXPECTED_URL_PREFIX + " (got: " + skin.textureUrl() + ")");
                parsed++;
            }
        }
        assertTrue(parsed >= MIN_EXPECTED_SKINS,
            "Default pack should ship at least " + MIN_EXPECTED_SKINS
            + " skins (found " + parsed + ")");
    }

    /**
     * Resolve the skins directory on the test classpath. We resolve by URL
     * (not by package-local class loading) so the test still passes when the
     * resources have been jar'd up.
     */
    private static Path resolveSkinsDir() {
        URL url = DefaultSkinPackTest.class.getResource(SKINS_RESOURCE_PATH);
        assertNotNull(url, "Default skin pack resource folder not on classpath: "
                          + SKINS_RESOURCE_PATH);
        try (InputStream ignored = url.openStream()) {
            return Paths.get(url.toURI());
        } catch (Exception e) {
            throw new RuntimeException("Could not resolve " + SKINS_RESOURCE_PATH, e);
        }
    }
}
