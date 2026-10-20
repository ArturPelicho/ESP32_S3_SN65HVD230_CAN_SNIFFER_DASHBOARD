package btools.router;

/** Reads BRouter's turn instructions for the app (the fields are package-private). */
public final class HintAccess {
  private HintAccess() {
  }

  /** Each hint as {index in the track's nodes, command, roundabout exit}. */
  public static int[][] hints(OsmTrack t) {
    if (t == null || t.voiceHints == null) return new int[0][];
    int[][] out = new int[t.voiceHints.list.size()][];
    int i = 0;
    for (VoiceHint h : t.voiceHints.list) out[i++] = new int[]{h.indexInTrack, h.cmd, h.roundaboutExit};
    return out;
  }
}
