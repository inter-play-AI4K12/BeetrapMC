package beetrap.btfmc.textandspeech;

import static beetrap.btfmc.BeetrapfabricmcClient.MOD_ID;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class SimpleVoiceChatPlugin implements VoicechatPlugin {

    private static final Logger LOG = LogManager.getLogger(SimpleVoiceChatPlugin.class);
    private static final long VOICE_TIMEOUT_MS = 500; // 1 second of silence indicates end of transmission
    private ByteArrayOutputStream audioBuffer;
    private long lastAudioTimestamp;
    private boolean isRecording = false;
    private ExecutorService es;


    @Override
    public void initialize(VoicechatApi api) {
        audioBuffer = new ByteArrayOutputStream();
        lastAudioTimestamp = 0;

        // Register tick event to check for voice transmission timeout
        ClientTickEvents.START_WORLD_TICK.register(this::onStartWorldTick);
        this.es = Executors.newSingleThreadExecutor();
    }

    private void onStartWorldTick(ClientWorld clientWorld) {
        long currentTime = System.currentTimeMillis();
        // Check if we're recording and if the timeout has been reached
        if(isRecording && currentTime - lastAudioTimestamp > VOICE_TIMEOUT_MS
                && audioBuffer.size() > 0) {
            LOG.info(
                    "Voice transmission timeout detected! Time since last audio: {} ms, Buffer size: {} bytes",
                    currentTime - lastAudioTimestamp, audioBuffer.size());
            // Process the completed voice transmission
            processVoiceTransmission();
        }
    }

    @Override
    public String getPluginId() {
        return MOD_ID + "-SimpleVoiceChatPlugin";
    }

    public void onClientSoundEvent(ClientSoundEvent cse) {
        long currentTime = System.currentTimeMillis();

        // If not recording, start a new recording
        if(!isRecording) {
            LOG.info("Starting new voice recording");
            audioBuffer.reset();
            isRecording = true;
        }
        // If we've timed out but still receiving audio, it's a new transmission
        else if(currentTime - lastAudioTimestamp > VOICE_TIMEOUT_MS && audioBuffer.size() > 0) {
            LOG.info("New voice transmission after timeout");
            // Process the previous voice transmission
            processVoiceTransmission();
            // Start a new recording
            audioBuffer.reset();
            isRecording = true;
        }

        try {
            short[] rawAudio = cse.getRawAudio();
            // Convert shorts to bytes preserving 16-bit PCM format
            byte[] audioBytes = new byte[rawAudio.length * 2];
            for(int i = 0; i < rawAudio.length; i++) {
                // Little-endian format
                audioBytes[i * 2] = (byte)(rawAudio[i] & 0xff);
                audioBytes[i * 2 + 1] = (byte)((rawAudio[i] >> 8) & 0xff);
            }
            audioBuffer.write(audioBytes);
            lastAudioTimestamp = currentTime;
        } catch(IOException e) {
            LOG.error("Failed to write audio data to buffer", e);
        }
    }

    private void processVoiceTransmission() {
        if(audioBuffer.size() == 0) {
            LOG.info("Skipping voice processing - empty buffer");
            return;
        }

        try {
            // Get the complete audio data
            byte[] completeAudio = audioBuffer.toByteArray();
            LOG.info("Processing voice transmission of {} bytes", completeAudio.length);

            // 1. Save the audio to a file
            Path audioFilePath = saveAudioToFile(completeAudio);
            if(audioFilePath == null) {
                LOG.error("Failed to save audio file, cannot proceed with transcription");
                return;
            }

//            // 2. Replay the audio
//            LOG.info("Replaying audio...");
//            replayAudio(completeAudio);
//            LOG.info("Audio replay completed");

            // 3. Transcribe using Whisper API with the saved WAV file
            LOG.info("Transcribing audio file: {}", audioFilePath);

            this.es.submit(() -> {
                String transcription = SpeechToTextUtil.transcribeAudioFile(audioFilePath);
                // Silence or noise can transcribe to nothing; a blank chat would make Bip reply to
                // an empty message (the service rejects that), so just drop it.
                if(transcription == null || transcription.isBlank()) {
                    LOG.info("Transcription was empty, not sending it to chat");
                    return;
                }
                MinecraftClient.getInstance().player.networkHandler.sendChatMessage(transcription);
                LOG.info("Transcription: " + transcription);
            });
        } catch(Exception e) {
            LOG.error("Error processing voice transmission", e);
        } finally {
            // Reset for next transmission
            LOG.info("Resetting audio buffer and recording state");
            audioBuffer.reset();
            isRecording = false;
        }
    }

    /**
     * Saves audio data to a WAV file.
     *
     * @param audioData Raw audio data as byte array
     * @return Path to the saved file, or null if saving failed
     */
    private Path saveAudioToFile(byte[] audioData) {
        try {
            String fileName = "voice_recording_" + System.currentTimeMillis() + ".wav";
            Path filePath = Paths.get(System.getProperty("user.dir"), "recordings", fileName);
            LOG.info("Saving audio file to: {}", filePath.toAbsolutePath());

            // Ensure directory exists
            Path parentDir = filePath.getParent();
            if(!parentDir.toFile().exists()) {
                LOG.info("Creating directory: {}", parentDir);
                parentDir.toFile().mkdirs();
            }

            // Create WAV file with proper headers
            LOG.info("Writing {} bytes of audio data to WAV file", audioData.length);
            try(FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
                // Write WAV header (16-bit PCM, mono, 48000Hz)
                writeWavHeader(fos, audioData.length);
                // Write audio data
                fos.write(audioData);
            }

            LOG.info("Successfully saved audio recording to: {}", filePath);
            return filePath;
        } catch(IOException e) {
            LOG.error("Failed to save audio recording", e);
            return null;
        }
    }

    private void writeWavHeader(FileOutputStream fos, int audioDataLength) throws IOException {
        // WAV header format (simplified)
        int sampleRate = 48000; // VoiceChat uses 48kHz
        int channels = 1; // Mono
        int bitsPerSample = 16; // 16-bit PCM

        // RIFF header
        fos.write("RIFF".getBytes());
        fos.write(intToBytes(36 + audioDataLength)); // File size - 8
        fos.write("WAVE".getBytes());

        // fmt chunk
        fos.write("fmt ".getBytes());
        fos.write(intToBytes(16)); // Chunk size
        fos.write(shortToBytes((short)1)); // Audio format (1 = PCM)
        fos.write(shortToBytes((short)channels)); // Channels
        fos.write(intToBytes(sampleRate)); // Sample rate
        fos.write(intToBytes(sampleRate * channels * bitsPerSample / 8)); // Byte rate
        fos.write(shortToBytes((short)(channels * bitsPerSample / 8))); // Block align
        fos.write(shortToBytes((short)bitsPerSample)); // Bits per sample

        // data chunk
        fos.write("data".getBytes());
        fos.write(intToBytes(audioDataLength)); // Chunk size
    }

    private byte[] intToBytes(int value) {
        return new byte[]{
                (byte)(value & 0xff),
                (byte)((value >> 8) & 0xff),
                (byte)((value >> 16) & 0xff),
                (byte)((value >> 24) & 0xff)
        };
    }

    private byte[] shortToBytes(short value) {
        return new byte[]{
                (byte)(value & 0xff),
                (byte)((value >> 8) & 0xff)
        };
    }

    private void replayAudio(byte[] audioData) {
        // Implementation depends on how you want to play audio
        // You could use Java Sound API or another library
        // Example using JavaSound:
        try {
            LOG.info("Setting up audio format for replay (48kHz, 16-bit PCM, mono)");
            // Create an audio format for 16-bit PCM, mono, 48kHz
            AudioFormat format = new AudioFormat(48000, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

            LOG.info("Opening audio line for playback");
            try(SourceDataLine line = (SourceDataLine)AudioSystem.getLine(info)) {
                line.open(format);
                line.start();

                LOG.info("Writing {} bytes of audio data to playback line", audioData.length);
                line.write(audioData, 0, audioData.length);

                LOG.info("Draining audio line (waiting for playback to complete)");
                line.drain();

                LOG.info("Audio playback completed");
            }
        } catch(Exception e) {
            LOG.error("Failed to replay audio", e);
        }
    }


    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(ClientSoundEvent.class, this::onClientSoundEvent);
    }
}
