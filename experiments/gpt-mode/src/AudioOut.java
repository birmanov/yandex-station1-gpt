/** Speaker playback. Implementations: AndroidAudio (AudioTrack), AlsaAudio (tinyplay). */
public interface AudioOut {
  /** Plays PCM16 mono at {@code sampleRate}; returns early when {@code cancel} is requested. */
  void play(byte[] pcm, int sampleRate, Cancel cancel) throws Exception;
}
