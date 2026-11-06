package beetrap.btfmc.tts;

import static beetrap.btfmc.Beetrapfabricmc.MOD_REQUIRED_OPENAI_API_KEY;
import static beetrap.btfmc.Beetrapfabricmc.MOD_REQUIRED_TYPECAST_API_KEY;

import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Bip's voice. Typecast first; if Typecast fails (e.g. the account ran out of credits), OpenAI's
 * speech API takes over so Bip keeps talking. Failures are logged instead of failing silently.
 */
public final class SlopTextToSpeechUtil {

    private static final Logger LOG = LogManager.getLogger(SlopTextToSpeechUtil.class);
    private static final String TYPECAST_API_KEY = System.getProperty(
            MOD_REQUIRED_TYPECAST_API_KEY);
    private static final String OPENAI_API_KEY = System.getProperty(
            MOD_REQUIRED_OPENAI_API_KEY);
    private static final CloseableHttpClient httpClient;
    private static final String requestBody;
    /** Network fetches run here so a download never blocks behind audio that is playing. */
    private static final ExecutorService fetchEs;
    /** Playback is single-threaded so audio lines never overlap (one speaker at a time). */
    private static final ExecutorService playbackEs;

    // OpenAI fallback voice. gpt-4o-mini-tts takes a speaking-style instruction; tts-1 is the
    // older model, tried if the first one is refused.
    private static final String[] OPENAI_TTS_MODELS = {"gpt-4o-mini-tts", "tts-1"};
    private static final String OPENAI_TTS_VOICE = "coral";
    private static final String OPENAI_TTS_INSTRUCTIONS = "Speak like a cheerful, friendly young "
            + "bee chatting with a kid. Warm and upbeat, not too fast.";
    private static final AudioFormat OPENAI_PCM_FORMAT = new AudioFormat(24000f, 16, 1, true, false);

    /** Set once Typecast refuses us (no credits / bad key), so later lines skip straight to OpenAI. */
    private static volatile boolean typecastUnavailable;
    private static volatile int openAiModelIndex;

    static {
        httpClient = HttpClients.createDefault();
        requestBody = """
                {
                    "model": "ssfm-v21",
                    "text": "{}",
                    "voice_id": "tc_660e5c11eef728e75f95f520"
                }
                """;

        fetchEs = Executors.newCachedThreadPool(daemonThreadFactory());
        playbackEs = Executors.newSingleThreadExecutor(daemonThreadFactory());
    }

    private static java.util.concurrent.ThreadFactory daemonThreadFactory() {
        return r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        };
    }

    private SlopTextToSpeechUtil() {
        throw new AssertionError();
    }

    /** An HTTP error from a TTS provider, with the start of its response body for the log. */
    private static final class TtsHttpException extends IOException {
        final int status;

        TtsHttpException(int status, String detail) {
            super("HTTP " + status + ": " + detail);
            this.status = status;
        }
    }

    private static byte[] fetchBytes(ClassicHttpRequest request) throws IOException {
        return httpClient.execute(request, response -> {
            byte[] bytes = response.getEntity() == null
                    ? new byte[0] : EntityUtils.toByteArray(response.getEntity());
            if(response.getCode() >= 300) {
                String detail = new String(bytes, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
                throw new TtsHttpException(response.getCode(),
                        detail.length() > 300 ? detail.substring(0, 300) : detail);
            }
            return bytes;
        });
    }

    private static void fetchTypecast(String message, File out) throws IOException {
        String cleanMessage = message.replaceAll("[\\n\\t]", " ").replaceAll("[^a-zA-Z0-9 ]", "");
        ClassicHttpRequest tts = ClassicRequestBuilder.post("https://api.typecast.ai/v1/text-to-speech")
                .addHeader("X-API-KEY", TYPECAST_API_KEY)
                .addHeader("Content-Type", "application/json")
                .setEntity(requestBody.replace("{}", cleanMessage))
                .build();
        Files.write(out.toPath(), fetchBytes(tts));
    }

    /** OpenAI speech as raw 24 kHz PCM, wrapped into a proper WAV so the duration reads right. */
    private static void fetchOpenAi(String message, File out) throws IOException {
        if(OPENAI_API_KEY == null || OPENAI_API_KEY.isBlank()) {
            throw new IOException("no TTS left: Typecast failed and OPENAI_API_KEY is not set");
        }
        while(true) {
            int index = openAiModelIndex;
            String model = OPENAI_TTS_MODELS[index];
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("voice", OPENAI_TTS_VOICE);
            body.addProperty("input", message.replaceAll("[\\n\\t]", " "));
            body.addProperty("response_format", "pcm");
            if(model.startsWith("gpt-")) {
                body.addProperty("instructions", OPENAI_TTS_INSTRUCTIONS);
            }
            ClassicHttpRequest tts = ClassicRequestBuilder.post("https://api.openai.com/v1/audio/speech")
                    .addHeader("Authorization", "Bearer " + OPENAI_API_KEY)
                    .setEntity(body.toString(), ContentType.APPLICATION_JSON)
                    .build();
            try {
                byte[] pcm = fetchBytes(tts);
                try(AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(pcm),
                        OPENAI_PCM_FORMAT, pcm.length / OPENAI_PCM_FORMAT.getFrameSize())) {
                    AudioSystem.write(ais, AudioFileFormat.Type.WAVE, out);
                }
                return;
            } catch(TtsHttpException e) {
                boolean modelRefused = e.status == 400 || e.status == 403 || e.status == 404;
                if(!modelRefused || index + 1 >= OPENAI_TTS_MODELS.length) {
                    throw e;
                }
                LOG.warn("OpenAI TTS model {} refused ({}); trying {}", model, e.getMessage(),
                        OPENAI_TTS_MODELS[index + 1]);
                openAiModelIndex = index + 1;
            }
        }
    }

    /** Fetch Bip's line into {@code out}: Typecast, else OpenAI. */
    private static void fetchSpeech(String message, File out) throws IOException {
        boolean haveTypecast = TYPECAST_API_KEY != null && !TYPECAST_API_KEY.isBlank();
        if(haveTypecast && !typecastUnavailable) {
            try {
                fetchTypecast(message, out);
                return;
            } catch(IOException e) {
                if(e instanceof TtsHttpException h && h.status >= 400 && h.status < 500) {
                    // Out of credits / bad key won't fix itself mid-session: stop asking.
                    typecastUnavailable = true;
                    LOG.warn("Typecast TTS refused ({}); Bip will use OpenAI TTS for this run",
                            e.getMessage());
                } else {
                    LOG.warn("Typecast TTS failed ({}); using OpenAI TTS for this line",
                            e.getMessage());
                }
            }
        }
        try {
            fetchOpenAi(message, out);
        } catch(IOException e) {
            LOG.warn("Bip TTS failed, showing text only: {}", e.getMessage());
            throw e;
        }
    }

    private static void playAudioFile(File f) {
        try {
            // Get audio input stream from the file
            AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(f);

            // Get the audio format
            AudioFormat format = audioInputStream.getFormat();
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

            // Get and open the audio line
            SourceDataLine line = (SourceDataLine)AudioSystem.getLine(info);
            line.open(format);
            line.start();

            // Create buffer for reading
            int bufferSize = 4096;
            byte[] buffer = new byte[bufferSize];
            int bytesRead;

            // Read and play the audio file
            while((bytesRead = audioInputStream.read(buffer, 0, buffer.length)) != -1) {
                line.write(buffer, 0, bytesRead);
            }

            // Clean up
            line.drain();
            line.stop();
            line.close();
            audioInputStream.close();
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Result of TTS generation containing audio duration and playback control
     */
    public static class TTSResult {
        public final double durationSeconds;
        public final CompletableFuture<Void> playbackFuture;

        public TTSResult(double durationSeconds, CompletableFuture<Void> playbackFuture) {
            this.durationSeconds = durationSeconds;
            this.playbackFuture = playbackFuture;
        }
    }

    private static double getAudioDuration(File audioFile) {
        try {
            AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(audioFile);
            AudioFormat format = audioInputStream.getFormat();
            long frames = audioInputStream.getFrameLength();
            double durationInSeconds = (frames + 0.0) / format.getFrameRate();
            audioInputStream.close();
            return durationInSeconds;
        } catch (Exception e) {
            // Fallback estimation based on text length (average speaking rate ~150 WPM)
            return 0.4; // Default 0.4 seconds for short text
        }
    }

    /**
     * Generate TTS with duration information for proportional text display
     */
    public static CompletableFuture<TTSResult> sayWithDuration(String message) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                File audioFile = new File("imagine" + System.currentTimeMillis() + ".wav");

                // Download and save audio file first
                fetchSpeech(message, audioFile);

                // Get audio duration
                double duration = getAudioDuration(audioFile);

                // Create playback future
                CompletableFuture<Void> playbackFuture = CompletableFuture.runAsync(() -> {
                    playAudioFile(audioFile);
                    // Clean up temp file after playback
                    audioFile.delete();
                }, playbackEs);

                return new TTSResult(duration, playbackFuture);

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, fetchEs);
    }

    // TODO: Add proximity thingies
    public static CompletableFuture<?> say(String message) {
        return CompletableFuture.runAsync(() -> {
            File audioFile = new File("imagine" + System.currentTimeMillis() + ".wav");
            try {
                fetchSpeech(message, audioFile);
                playAudioFile(audioFile);
            } catch(IOException e) {
                throw new RuntimeException(e);
            } finally {
                audioFile.delete();
            }
        }, playbackEs);
    }
}
