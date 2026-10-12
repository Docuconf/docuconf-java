package dev.docuconf.spring;

import java.time.Instant;

/**
 * The reload state of one file input declared with {@code reload = WATCH} (SPEC §4.6.2), for a health check or a
 * metric. It never holds file content. Read it with {@link DocuconfFiles#reloadStatus(String)}.
 *
 * @param generation 1 after boot, plus one per accepted reload; 0 for an optional input that was absent at boot
 *     and has not appeared since
 * @param lastReload when the last accepted reload happened, or {@code null} if the input has not been reloaded
 *     since boot
 * @param lastRejected the last change that failed its checks and was not used, or {@code null}; an accepted reload
 *     clears it
 */
public record ReloadStatus(long generation, Instant lastReload, RejectedReload lastRejected) {
}
