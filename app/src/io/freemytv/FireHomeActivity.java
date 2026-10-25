package io.freemytv;

/**
 * The home screen when opened as an app on Fire TV (where Amazon keeps the Home button). Unlike {@link HomeActivity},
 * its manifest entry omits {@code resumeWhilePausing}: on Fire OS that attribute on a non-HOME activity leaves window
 * focus on the pausing launcher and drops remote key events.
 */
public class FireHomeActivity extends HomeActivity {}
