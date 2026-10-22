package com.kzplayer.app;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V116 : refuse un manifeste HLS qui annonce un horaire hors programme.
 * Aucun acces reseau. Les dates ISO-8601 sont comparees en UTC, fuseaux inclus.
 * Sans horodatage exploitable, le resultat est inconnu (false), PAS une preuve
 * que le contenu est bien une archive. Le candidat doit rester un lien timeshift.
 */
public final class ReplayArchiveGuard {
    private static final Pattern PDT = Pattern.compile(
        "(?m)^#EXT-X-PROGRAM-DATE-TIME:[ \t]*([^\r\n]+)"
    );
    private static final long START_MARGIN_MS = 15L * 60L * 1000L;
    private static final long END_MARGIN_MS = 2L * 60L * 1000L;

    private ReplayArchiveGuard() { }

    public static boolean isOutsideProgram(String body, long startMs, long endMs) {
        if (body == null || startMs <= 0L || endMs <= startMs) return false;
        Matcher matcher = PDT.matcher(body);
        // Le premier PDT correspond au debut de la fenetre du flux retourne.
        // Une playlist de direct peut ne pas contenir ENDLIST : ce marqueur
        // seul n'est pas une preuve car certains replays sont aussi glissants.
        while (matcher.find()) {
            try {
                long actualMs = OffsetDateTime.parse(matcher.group(1).trim())
                    .toInstant().toEpochMilli();
                return actualMs < startMs - START_MARGIN_MS ||
                    actualMs > endMs + END_MARGIN_MS;
            } catch (DateTimeParseException ignored) {
                // Une valeur invalide n'empeche pas de tester le PDT suivant.
            }
        }
        return false;
    }
}
