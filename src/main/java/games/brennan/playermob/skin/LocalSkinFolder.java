package games.brennan.playermob.skin;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The {@code config/playermob/skins/} drop folder — players put {@code .png} skin files here and
 * PlayerMobs can wear them (randomly, gated by the {@code local} source toggle, or by filename via
 * {@code /playermob skin spawn}). Resolved once at boot from the loader config dir passed to
 * {@link games.brennan.playermob.PlayerMob#init(Path)}, so the same path is available on both the
 * integrated/dedicated server (which lists names to pick from) and the client (which loads the PNGs —
 * see {@code LocalSkinTextures}).
 *
 * <p>A skin is referred to by its <em>base name</em> (the filename without the {@code .png} extension)
 * — that is what the random ref encodes ({@link LocalSkinRef}) and what the spawn command takes. The
 * directory listing is cached briefly so per-spawn rolls don't hit the filesystem every time, while
 * still picking up newly dropped files within a few seconds.</p>
 */
public final class LocalSkinFolder {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long CACHE_TTL_MS = 3_000L;

    private static volatile Path dir;
    private static volatile List<String> cachedNames = List.of();
    /** Last scan time (ms); {@code 0} means "stale, rescan on next list()". Never use a large negative
     *  sentinel here — {@code now - sentinel} would overflow and read as "fresh", wedging the cache. */
    private static volatile long cachedAt = 0L;

    private LocalSkinFolder() {}

    /**
     * Point the folder at {@code <configDir>/playermob/skins}, creating it if absent. Called once from
     * {@link games.brennan.playermob.PlayerMob#init(Path)} on each physical side. Never throws — a
     * failure to create the folder logs and leaves the source empty (so the feature simply contributes
     * no skins rather than breaking init).
     */
    public static void init(Path configDir) {
        if (configDir == null) {
            return;
        }
        Path resolved = configDir.resolve("playermob").resolve("skins");
        try {
            Files.createDirectories(resolved);
        } catch (IOException e) {
            LOGGER.warn("[playermob] could not create local skins folder {}; local skins disabled", resolved, e);
        }
        dir = resolved;
        cachedAt = 0L;     // force a fresh listing on first use
    }

    /** The skins folder, or {@code null} before {@link #init} runs. */
    public static Path dir() {
        return dir;
    }

    /**
     * The available local skin base names (filenames without {@code .png}), sorted, de-duplicated.
     * Cached for {@value #CACHE_TTL_MS} ms so spawn rolls don't re-scan the disk every call. Empty when
     * the folder is unset/unreadable.
     */
    public static List<String> list() {
        Path d = dir;
        if (d == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        if (!dueForRescan(now, cachedAt)) {
            return cachedNames;
        }
        List<String> names = scan(d);
        cachedNames = names;
        cachedAt = now;
        return names;
    }

    /**
     * Whether the cache is due for a rescan — true once {@code now} is at least {@link #CACHE_TTL_MS}
     * past {@code cachedAt}. Subtraction-free (compares {@code now >= cachedAt + ttl}) so a {@code 0}
     * sentinel always reads stale and no value can overflow into a spurious "fresh" — the exact trap
     * that wedged an earlier version's cache. Package-private + pure for unit testing.
     */
    static boolean dueForRescan(long now, long cachedAt) {
        return now >= cachedAt + CACHE_TTL_MS;
    }

    /** Scan the folder for {@code *.png} files and return their sorted, de-duplicated base names. */
    private static List<String> scan(Path d) {
        if (!Files.isDirectory(d)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> entries = Files.list(d)) {
            entries.filter(Files::isRegularFile).forEach(p -> {
                String base = baseName(p);
                if (base != null && !names.contains(base)) {
                    names.add(base);
                }
            });
        } catch (IOException e) {
            LOGGER.warn("[playermob] could not list local skins folder {}", d, e);
            return List.of();
        }
        names.sort(String::compareToIgnoreCase);
        return List.copyOf(names);
    }

    /**
     * Resolve a base name to the actual {@code .png} file inside the folder, or {@code null} if the
     * name is unsafe, the folder is unset, or no matching file exists. Re-validates the resolved path is
     * really inside the folder (defence-in-depth against traversal beyond {@link LocalSkinRef#sanitize}).
     * Matches the base name case-insensitively so commands are forgiving.
     */
    public static Path resolve(String name) {
        Path d = dir;
        String safe = LocalSkinRef.sanitize(name);
        if (d == null || safe == null || !Files.isDirectory(d)) {
            return null;
        }
        try (Stream<Path> entries = Files.list(d)) {
            return entries
                .filter(Files::isRegularFile)
                .filter(p -> safe.equalsIgnoreCase(baseName(p)))
                .filter(p -> p.toAbsolutePath().normalize().startsWith(d.toAbsolutePath().normalize()))
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            LOGGER.warn("[playermob] could not resolve local skin '{}' in {}", safe, d, e);
            return null;
        }
    }

    /** The base name (filename minus a {@code .png} extension), or {@code null} for a non-PNG file. */
    private static String baseName(Path p) {
        String file = p.getFileName().toString();
        if (file.toLowerCase(Locale.ROOT).endsWith(".png") && file.length() > 4) {
            return file.substring(0, file.length() - 4);
        }
        return null;
    }
}
