package dev.docuconf;

/** How the app picks up a changed file input (SPEC §4.6.2). */
public enum Reload {
    /** The app reads the file at startup, so a changed source needs a rollout. */
    RESTART,
    /** The app reloads the file itself: docuconf watches the mount directory and notifies listeners. */
    WATCH
}
