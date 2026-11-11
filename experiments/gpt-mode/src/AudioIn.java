/** Microphone capture. Implementations: AndroidAudio (AudioRecord), AlsaAudio (tinycap). */
public interface AudioIn {
  /**
   * Records PCM16 mono 16 kHz until {@code maxMillis}, until {@code silenceMillis} of audio below
   * {@code silenceRms} follows speech, or until {@code cancel} is requested. Returns an empty
   * array when no speech was heard at all.
   */
  byte[] record(int maxMillis, int silenceMillis, int silenceRms, Cancel cancel) throws Exception;
}
