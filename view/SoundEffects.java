import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

// Bus subscriber: plays short procedurally-synthesized tones for game events - no
// sound files, no external library, just sine waves through the JDK's own audio API.
public class SoundEffects {
    private static final float SAMPLE_RATE = 44100f;

    public void subscribe(Bus bus) {
        bus.subscribe(GameSession.TOPIC_MOVE_RESOLVED, payload -> onMoveResolved((MoveEvent) payload));
        bus.subscribe(GameSession.TOPIC_GAME_OVER, payload -> onGameOver());
        bus.subscribe(GameSession.TOPIC_GAME_STARTED, payload -> onGameStarted());
    }

    private void onMoveResolved(MoveEvent e) {
        if (e.wasCapture) {
            playTone(220, 150, 0.3);
        } else {
            playTone(660, 80, 0.18);
        }
    }

    private void onGameOver() {
        playMelody(new double[]{523.25, 659.25, 783.99}, 150, 0.3);
    }

    private void onGameStarted() {
        playMelody(new double[]{440, 587.33}, 110, 0.22);
    }

    private void playMelody(double[] freqsHz, int noteDurationMs, double volume) {
        new Thread(() -> {
            for (double freq : freqsHz) {
                playToneBlocking(freq, noteDurationMs, volume);
            }
        }).start();
    }

    private void playTone(double freqHz, int durationMs, double volume) {
        new Thread(() -> playToneBlocking(freqHz, durationMs, volume)).start();
    }

    private void playToneBlocking(double freqHz, int durationMs, double volume) {
        try {
            int numSamples = (int) (durationMs / 1000.0 * SAMPLE_RATE);
            byte[] buffer = new byte[numSamples * 2];
            int fadeSamples = Math.min(200, numSamples / 4);

            for (int i = 0; i < numSamples; i++) {
                double angle = 2.0 * Math.PI * i * freqHz / SAMPLE_RATE;
                double envelope = Math.min(1.0, Math.min((double) i / fadeSamples, (double) (numSamples - i) / fadeSamples));
                short sample = (short) (Math.sin(angle) * volume * envelope * Short.MAX_VALUE);
                buffer[2 * i] = (byte) (sample & 0xff);
                buffer[2 * i + 1] = (byte) ((sample >> 8) & 0xff);
            }

            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            SourceDataLine line = AudioSystem.getSourceDataLine(format);
            line.open(format);
            line.start();
            line.write(buffer, 0, buffer.length);
            line.drain();
            line.close();
        } catch (LineUnavailableException e) {
            // no audio output device available - sound is a nice-to-have, fail silently
        }
    }
}
