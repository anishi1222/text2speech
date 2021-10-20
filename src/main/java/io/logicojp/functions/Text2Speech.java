package io.logicojp.functions;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.OutputBinding;
import com.microsoft.azure.functions.annotation.*;
import com.microsoft.cognitiveservices.speech.*;
import com.microsoft.cognitiveservices.speech.audio.AudioConfig;
import com.microsoft.cognitiveservices.speech.audio.AudioOutputStream;
import com.microsoft.cognitiveservices.speech.audio.PullAudioOutputStream;
import org.json.JSONArray;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

public class Text2Speech {

    static String speechKey;
    static String speechRegion;
    static List<Voice> voiceList;

    public Text2Speech() {
        speechKey = System.getenv(Text2SpeechConfig.SPEECH_KEY);
        speechRegion = System.getenv(Text2SpeechConfig.SPEECH_REGION);
        voiceList = new ArrayList<>();
    }

    void collectVoiceList() {
        String url = "https://" + speechRegion + Text2SpeechConfig.VOICELIST_FRAGMENT_URL;
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest httpRequest = HttpRequest.newBuilder()
            .setHeader(Text2SpeechConfig.SPEECH_SERVICE_KEY, speechKey)
            .uri(URI.create(url))
            .GET()
            .build();
        try {
            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (httpResponse.statusCode() == HttpStatus.OK.value()) {
                JSONArray jsonArray = new JSONArray(httpResponse.body());
                for (int i = 0; i < jsonArray.length(); i++) {
                    Voice voice = new Voice();
                    voice.setDisplayName(jsonArray.getJSONObject(i).getString("DisplayName"));
                    voice.setGender(jsonArray.getJSONObject(i).getString("Gender"));
                    voice.setLocale(jsonArray.getJSONObject(i).getString("Locale"));
                    voice.setVoiceType(jsonArray.getJSONObject(i).getString("VoiceType"));
                    voice.setLocalName(jsonArray.getJSONObject(i).getString("LocalName"));
                    voice.setName(jsonArray.getJSONObject(i).getString("Name"));
                    voice.setSampleRateHertz(jsonArray.getJSONObject(i).getString("SampleRateHertz"));
                    voice.setStatus(jsonArray.getJSONObject(i).getString("Status"));
                    voice.setShortName(jsonArray.getJSONObject(i).getString("ShortName"));
                    voiceList.add(voice);
                }
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    @FunctionName("Text2Speech-Java")
    @StorageAccount("AzureWebJobsStorage")
    public void run(
        @BlobTrigger(
            name = "source",
            path = "in-files/{inName}_{gender}_{locale}.txt",
            dataType = "binary")
            byte[] source,
        @BindingName("inName") String sourceFileName,
        @BindingName("gender") String gender,
        @BindingName("locale") String locale,
        @BlobOutput(
            name = "target",
            dataType = "binary",
            path = "out-files/{inName}_{gender}_{locale}.mp3")
            OutputBinding<byte[]> target,
        final ExecutionContext context
    ) {
        // Capture region and key
        if (Optional.of(speechKey).isEmpty()) {
            context.getLogger().severe("Environment variable [" + Text2SpeechConfig.SPEECH_KEY + "] is not found.");
            return;
        }
        if (Optional.of(speechRegion).isEmpty()) {
            context.getLogger().severe("Environment variable [" + Text2SpeechConfig.SPEECH_REGION + "] is not found.");
            return;
        }
        if (voiceList.isEmpty()) {
            collectVoiceList();
        }

        // Processing a file
        context.getLogger().info("Java Blob trigger function processed a blob.\nName: " + sourceFileName +
            "\n speech locale: " + locale +
            "\n gender: " + gender +
            "\n Size: " + source.length + " Bytes");

        // Looking for voice name
        context.getLogger().info("[gender] " + gender + " [locale] " + locale);
        Optional<Voice> optionalVoice = voiceList.stream()
            .filter(f -> f.getGender().equalsIgnoreCase(gender))
            .filter(f -> f.getLocale().equalsIgnoreCase(locale))
            .findFirst();

        if (optionalVoice.isEmpty()) {
            context.getLogger().severe("Voice is not found.");
            return;
        }

        SpeechConfig speechConfig = SpeechConfig.fromSubscription(speechKey, speechRegion);
        speechConfig.setSpeechSynthesisLanguage(locale);
        speechConfig.setSpeechSynthesisVoiceName(optionalVoice.get().getName());
        speechConfig.setSpeechSynthesisOutputFormat(SpeechSynthesisOutputFormat.Audio16Khz128KBitRateMonoMp3);
        String text = new String(source, Charset.defaultCharset());

        // Use AudioOutputStream to output audio data in the form of blob
        try (PullAudioOutputStream stream = AudioOutputStream.createPullStream();
             AudioConfig streamConfig = AudioConfig.fromStreamOutput(stream);
             SpeechSynthesizer synthesizer = new SpeechSynthesizer(speechConfig, streamConfig)) {

            // Verification
            SpeechSynthesisResult result = synthesizer.SpeakTextAsync(text).get();
            if (result.getReason() == ResultReason.SynthesizingAudioCompleted) {
                context.getLogger().info("Speech synthesized for text [" + text + "], and the audio was written to output stream.");
            } else if (result.getReason() == ResultReason.Canceled) {
                SpeechSynthesisCancellationDetails cancellation = SpeechSynthesisCancellationDetails.fromResult(result);
                context.getLogger().info("CANCELED: Reason=" + cancellation.getReason());

                if (cancellation.getReason() == CancellationReason.Error) {
                    context.getLogger().info("CANCELED: ErrorCode=" + cancellation.getErrorCode());
                    context.getLogger().info("CANCELED: ErrorDetails=" + cancellation.getErrorDetails());
                    context.getLogger().info("CANCELED: Did you update the subscription info?");
                }
            }
            // set bytes array to output binding
            target.setValue(result.getAudioData());
        } catch (ExecutionException | InterruptedException executionException) {
            executionException.printStackTrace();
            context.getLogger().severe(executionException.getLocalizedMessage());
        }
    }
}
