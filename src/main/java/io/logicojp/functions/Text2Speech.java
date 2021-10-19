package io.logicojp.functions;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.OutputBinding;
import com.microsoft.azure.functions.annotation.*;
import com.microsoft.cognitiveservices.speech.*;
import com.microsoft.cognitiveservices.speech.audio.AudioConfig;
import com.microsoft.cognitiveservices.speech.audio.AudioOutputStream;
import com.microsoft.cognitiveservices.speech.audio.PullAudioOutputStream;
import org.json.JSONArray;
import org.json.JSONTokener;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

public class Text2Speech {

    static List<Voice> voiceList;
    static String speechKey;
    static String speechRegion;

    public Text2Speech() {
        speechKey = System.getenv(Text2SpeechConfig.SPEECH_KEY);
        speechRegion = System.getenv(Text2SpeechConfig.SPEECH_REGION);

        // Collect voiceList
        ;
        try (InputStream is = Text2Speech.class.getResourceAsStream(Text2SpeechConfig.VOICELIST_FILE)) {
            JSONArray jsonArray = new JSONArray(new JSONTokener(is));
            voiceList = new ArrayList<Voice>();
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
        } catch (IOException e) {
            e.printStackTrace();
        }
        voiceList.forEach(f -> System.out.println(f.toString()));
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

        // Processing a file
        context.getLogger().info("Java Blob trigger function processed a blob. Name: " + sourceFileName +
            "\n speech locale: " + locale +
            "\n Size: " + source.length + " Bytes");

        // Looking for voice name
        String voiceName = voiceList.stream()
            .filter(f -> f.getGender().equalsIgnoreCase(gender))
            .filter(f -> f.getLocale().equalsIgnoreCase(locale))
            .findFirst().get().getName();

        SpeechConfig speechConfig = SpeechConfig.fromSubscription(speechKey, speechRegion);
        speechConfig.setSpeechSynthesisLanguage(locale);
        speechConfig.setSpeechSynthesisVoiceName(voiceName);
        speechConfig.setSpeechSynthesisOutputFormat(SpeechSynthesisOutputFormat.Audio16Khz128KBitRateMonoMp3);
        String text = new String(source, Charset.defaultCharset());

        // Blobに書き出したいので、AudioOutputStreamで受け取る
        try (PullAudioOutputStream stream = AudioOutputStream.createPullStream();
             AudioConfig streamConfig = AudioConfig.fromStreamOutput(stream);
             SpeechSynthesizer synthesizer = new SpeechSynthesizer(speechConfig, streamConfig)) {

            // 結果の確認
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
            // 結果を取得し、バイト列に書き出す
            target.setValue(result.getAudioData());
        } catch (ExecutionException | InterruptedException executionException) {
            executionException.printStackTrace();
            context.getLogger().severe(executionException.getLocalizedMessage());
        }
    }
}
