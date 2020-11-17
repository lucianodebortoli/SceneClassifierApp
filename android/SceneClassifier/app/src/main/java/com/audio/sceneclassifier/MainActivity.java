package com.audio.sceneclassifier;

//           LUCIANO DE BORTOLI
//          INGENIERIA EN SONIDO
// UNIVERSIDAD NACIONAL DE TRES DE FEBRERO
//              2018 - 2019
//
//       REQUIRED CUSTOM CLASSES:
//          -InformationActivity.java
//          -MelSpectrogram.java
//          -SpectrogramView.java

import android.Manifest;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

import org.tensorflow.lite.Interpreter;
import static java.util.Locale.*;

public class MainActivity extends AppCompatActivity implements AdapterView.OnItemSelectedListener {


    //------------------------------------- UI OBJECTS ---------------------------------------------

    TextView predictionView_0, predictionView_1, predictionView_2, languageTextView,
             bufferTextView, bufferEditTextView, dynamicRangeTextViewEdit, dynamicRangeTextView,
            xLabelTextView, colorMapTextView, integrationTextView, sampleRateTV, inferenceTimeTV;
    Button logButton, validateButton, runButton, infoButton;
    ProgressBar predictionBar1, predictionBar2, predictionBar3, loadingBar, horizontalProgressBar;
    ImageView spectrogramImageView, colorBarImageView;
    Spinner colorMapSpinner, integrationSpinner, languageSpinner;
    Switch loopSwitch, compressorSwitch;

    // INITIALIZE VARIABLES:
    public int numClasses;
    public int sizeInFloats;
    public String saveName;
    //public int targetSamples = (frames+1)*hopLength; // = 132096
    //public int totalSamples = sampleRate* 4; // record for 4 seconds
    public int accuracy_0=0, accuracy_1=0, accuracy_2=0;
    public float[] pred_0, pred_1, pred_2, pred_3, pred_4;
    //public float[] audioSignal = new float[targetSamples];
    public float[][] spectrogram = new float[MEL_BINS][FRAMES];
    private static final int STORAGE_CODE =     1;
    public static final int RECORD_AUDIO_CODE = 200;
    //public boolean compressorOn = false;
    public String dynamicRange, label_0="...", label_1="...", label_2="...";
    public String modelFileName = "CNN.tflite";
    public String currentLanguage = "ENGLISH";
    public String currentColorMap = "MAGMA";
    public List<String> labels;
    String[] labelsArray;
    public String directory = Environment.getExternalStorageDirectory()+"/SceneClassifier";


    // New var
    private static int FFT_SIZE = 2048;
    private static int HOP_SIZE = 1024;
    private static int FRAMES = 128;
    private static int MEL_BINS = 128;
    private int SAMPLE_RATE = 16000;
    private int RECORDING_LENGTH = 132096;
    private static final String LOG_TAG = "tag_main";
    short[] recRingBuffer = new short[RECORDING_LENGTH];
    int recordingOffset = 0; // used as index for recording audio into circular buffer.
    private final ReentrantLock ringLock = new ReentrantLock(); // lock thread while copying buffer.
    boolean recording = true;
    boolean isRecording = false;
    boolean recognizing = true;
    boolean isRecognizing = false;
    private Thread recordingThread;
    private Thread recognitionThread;
    private long lastProcessingTimeMs;
    private static Interpreter tflite;
    private Detector detector;
    private int SMOOTH_SIZE;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private static final String HANDLE_THREAD_NAME = "CameraBackground";
    short[] inputBuffer16 = new short[RECORDING_LENGTH];
    float[] inputBuffer32 = new float[RECORDING_LENGTH];


    // startRecording is called only once, after recording permissions have been granted.
    public synchronized void startRecording() {
        if (recordingThread != null) { return; }
        recording = true;
        recordingThread =
                new Thread(this::record);
        recordingThread.start();
        Log.d(LOG_TAG, "recording started");
    } // startRecording end OK


    // stopRecording method is unused, so the recording thread is in an endless loop.
    public synchronized void stopRecording() {
        if (recordingThread == null) {return; }
        recording = false;
        recordingThread = null;
        Log.d(LOG_TAG, "recording stopped");
    } // stopRecording end OK


    // record method is called only once, its an endless loop.
    private void record() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);

        Log.v(LOG_TAG,"Sample Rate: " + SAMPLE_RATE);
        Log.v(LOG_TAG,"Ring Buffer Length: " + RECORDING_LENGTH);

        recRingBuffer = new short[RECORDING_LENGTH];
        // Get min buffer size from device:
        int minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            minBufferSize = SAMPLE_RATE * 2; }
        Log.v(LOG_TAG, "Min buffer length: " + minBufferSize);

        short[] audioBuffer = new short[minBufferSize / 2];
        Log.v(LOG_TAG, "Audio buffer length: " + audioBuffer.length);

        AudioRecord record =
                new AudioRecord(
                        MediaRecorder.AudioSource.DEFAULT,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBufferSize);

        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(LOG_TAG, "Audio Record can't initialize!");
            return; }

        record.startRecording();

        // CIRCULAR BUFFER RECORDING
        while (recording) {

            // Endless Loop, gathering audio data and copying it to a round-robin buffer.
            isRecording = true;
            int numberRead = record.read(audioBuffer, 0, audioBuffer.length);
            int maxLength = recRingBuffer.length;
            // writing pointer increment
            int newRecordingOffset = recordingOffset + numberRead;
            // Samples to copy to the start. Its zero until writing pointer overflows the circular buffer.
            int secondCopyLength = Math.max(0, newRecordingOffset - maxLength);
            // Samples to copy at writing pointer. its the buffer size until writing pointer overflows.
            int firstCopyLength = numberRead - secondCopyLength;
            // All data is stored for the recognition thread to access.
            // ML thread copies from this buffer into its own, while holding the lock, so its thread safe.
            ringLock.lock();

            try {
                // Save read samples from audioBuffer into RingBuffer at index given by writing pointer.
                System.arraycopy(audioBuffer, 0, recRingBuffer, recordingOffset, firstCopyLength);
                // If recPointer overflows, copy remaining read samples to the start of the RingBuffer.
                System.arraycopy(audioBuffer, firstCopyLength, recRingBuffer, 0, secondCopyLength);
                // Use modulus division for restoring overflown writing pointer
                recordingOffset = newRecordingOffset % maxLength;
            } finally { ringLock.unlock(); }}

        // only if recording stops:
        isRecording = false;
        Log.d(LOG_TAG,"Recording Stopped");
        record.stop();
        record.release();
    } // record end OK


    // startRecognition is called only once, after recording permissions have been granted.
    public synchronized void startRecognition() {
        if (recognitionThread != null) { return; }
        recognizing = true;
        recognitionThread = new Thread(this::recognize);
        recognitionThread.start();
        Log.d(LOG_TAG, "Start Recognition");
    } // startRecognition end OK


    // stopRecognition method is unused, so the recognition thread is in an endless loop.
    public synchronized void stopRecognition() {
        if (recognitionThread == null) {return; }
        recognizing = false;
        recognitionThread = null;
        Log.d(LOG_TAG, "Stop Recognition");
    } // stopRecognition OK


    // recognize method is called only once, its an endless loop.
    private void recognize() {
        // Loop, grabbing recorded data and running the recognition model on it.

        float[][][][] inputTensor = new float[1][FRAMES][MEL_BINS][1]; // if using spectrogram input
        float[][] outputTensor = new float[1][labels.size()]; // TensorFlow output array
        Log.d(LOG_TAG, "Using " + FRAMES + " time frames and " + MEL_BINS + " frequencies");

        while (recognizing) {
            long startTime = System.currentTimeMillis();
            isRecognizing = true;

            // Copy global parameters to prevent unwanted behaviour while changing models:
            int sampleRate = SAMPLE_RATE;
            int fftSize = FFT_SIZE;
            int bins = MEL_BINS;
            int frames = FRAMES;

            // locking to prevent ring buffer writing while reading into local buffer.
            ringLock.lock();
            try {
                int maxLength = recRingBuffer.length;
                // Samples available for reading from writing pointer position to the end of circular buffer.
                int firstCopyLength = maxLength - recordingOffset;
                // Samples available for reading from start of circular buffer to writing pointer position.
                int secondCopyLength = recordingOffset;
                // Read oldest samples from circular buffer and store them at the start of the inputBuffer.
                System.arraycopy(recRingBuffer, recordingOffset, inputBuffer16, 0, firstCopyLength);
                // Read newest samples from overflown circular buffer and fill the inputBuffer.
                System.arraycopy(recRingBuffer, 0, inputBuffer16, firstCopyLength, secondCopyLength);
            } finally { ringLock.unlock(); }

            // Now inputBuffer holds an array of the last 2 seconds of audio data.
            // Processes below, within this while loop, should take less than recording buffer length
            // so that the recording thread does not overwrite unread samples.

            // Convert signed 16-bit inputs into float values [-1.0f, 1.0f].
            float maxRes16 = (float) Math.pow(2, 15) -1;
            for (int i = 0; i < RECORDING_LENGTH; ++i)
                inputBuffer32[i] = inputBuffer16[i] / maxRes16;


            spectrogram = new MelSpectrogram(
                    inputBuffer32, sampleRate, MEL_BINS, frames, fftSize, HOP_SIZE).getSpectrogram();


            // Feed Spectrogram to input tensor:
            for (int frame = 0; frame< frames; frame++){
                for (int freq = 0; freq< bins; freq++) {
                    inputTensor[0][frame][freq][0] = spectrogram[freq][frame];}}

            // Run model inference and update detections:
            tflite.run(inputTensor, outputTensor);
            lastProcessingTimeMs = System.currentTimeMillis() - startTime;

            detector.add(outputTensor[0]);
            runOnUiThread(this::updateUI);

        } // while continue recognition (endless loop)
        isRecognizing = false;
        Log.d(LOG_TAG,"Stopped Recognizing");
    } // recognize end OK


    private void updateUI(){
        // Fill output tensor
        float[][] outputTensor = new float[1][labels.size()];
        for (int i=0; i<labels.size(); i++ )
            outputTensor[0][i] = detector.smoothDetections[i][0];

        savePredictions(outputTensor);
        updatePredictions();
        showUI();
        showDeveloperUI();
        showSpectrogram();
        sampleRateTV.setText(SAMPLE_RATE+ " Hz");
        inferenceTimeTV.setText(lastProcessingTimeMs + " ms");
    } // updateUI end


    private void initDetector(){
        SMOOTH_SIZE = 5;
        detector = new Detector(labels, SMOOTH_SIZE);
    } // initDetector end


    private void startBackgroundThread() {
        backgroundThread = new HandlerThread(HANDLE_THREAD_NAME);
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
        Log.d(LOG_TAG,"Background thread started");
    } // startBackgroundThread OK


    private void stopBackgroundThread() {
        backgroundThread.quitSafely();
        try {
            backgroundThread.join();
            backgroundThread = null;
            backgroundHandler = null;
        } catch (InterruptedException e) {
            Log.e(LOG_TAG, "Interrupted when stopping background thread", e);
        }
    } // stopBackgroundThread end OK


    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        Log.d(LOG_TAG,"App Resumed");
    } // onResume end OK


    @Override
    protected void onStop() {
        super.onStop();
        stopBackgroundThread();
        Log.d(LOG_TAG,"App Stopped");
    } // onStop end OK


    //----------------------------------- METHODS -------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        automaticCheckAudioPermission();
        setupUI();
        updateStrings();
        resetUI();
        init_model();
        initializeLogger();
        initDetector();
        startRecognition();
        Log.d(LOG_TAG, "UI: App Created!");
    } // onCreate method end


    private void setAppLocale(String code){
        Resources res = getResources();
        DisplayMetrics dm = res.getDisplayMetrics();
        Configuration conf = res.getConfiguration();
        conf.setLocale(new Locale(code.toLowerCase()));
        res.updateConfiguration(conf,dm);
    }


    private void setupUI(){
        // CALL LAYOUT OBJECTS:
        validateButton =            findViewById(R.id.validateButton);
        logButton =                 findViewById(R.id.logButton);
        predictionView_0 =          findViewById(R.id.prediction1TextView);
        predictionView_1 =          findViewById(R.id.prediction2TextView);
        predictionView_2 =          findViewById(R.id.prediction3TextView);
        bufferTextView =            findViewById(R.id.bufferTextView);
        bufferEditTextView =        findViewById(R.id.bufferEditTextView);
        xLabelTextView =            findViewById(R.id.xLabelTextView);
        dynamicRangeTextViewEdit =  findViewById(R.id.minTextView);
        dynamicRangeTextView =      findViewById(R.id.maxTextView);
        languageTextView =          findViewById(R.id.languageTextView);
        spectrogramImageView =      findViewById(R.id.spectrogramImageView);
        colorBarImageView =         findViewById(R.id.colorbarImageView);
        predictionBar1=             findViewById(R.id.progressBar1);
        predictionBar2=             findViewById(R.id.progressBar2);
        predictionBar3=             findViewById(R.id.progressBar3);
        loadingBar =                findViewById(R.id.loadingBar);
        horizontalProgressBar =     findViewById(R.id.horizontalProgressBar);
        colorMapTextView =          findViewById(R.id.colorMapTextView);
        integrationTextView =       findViewById(R.id.integrationTextView);
        colorMapSpinner =           findViewById(R.id.colorMapSpinner);
        integrationSpinner =        findViewById(R.id.integrationSpinner);
        runButton =                 findViewById(R.id.runButton);
        infoButton =                findViewById(R.id.infoButton);
        languageSpinner =           findViewById(R.id.languageSpinner);
        loopSwitch =                findViewById(R.id.loopSwitch);
        compressorSwitch =          findViewById(R.id.compressorSwitch);
        sampleRateTV =              findViewById(R.id.sampleRateTV);
        inferenceTimeTV =           findViewById(R.id.inferenceTimeTV);
        Log.d(LOG_TAG, "UI: Objects Loaded!");

        // LANGUAGE SPINNER:
        ArrayAdapter<CharSequence> languageAdapter = ArrayAdapter.createFromResource(
                this,R.array.language,android.R.layout.simple_spinner_item);
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(languageAdapter);
        languageSpinner.setOnItemSelectedListener(this);

        // INTEGRATION SPINNER:
        ArrayAdapter<CharSequence> integrationAdapter = ArrayAdapter.createFromResource(
                this,R.array.integration,android.R.layout.simple_spinner_item);
        integrationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        integrationSpinner.setAdapter(integrationAdapter);
        integrationSpinner.setOnItemSelectedListener(this);

        // COLORMAP SPINNER:
        ArrayAdapter<CharSequence> colorMapAdapter = ArrayAdapter.createFromResource(
                this,R.array.colorMap,android.R.layout.simple_spinner_item);
        colorMapAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        colorMapSpinner.setAdapter(colorMapAdapter);
        colorMapSpinner.setOnItemSelectedListener(this);

        // BUTTONS:
        validateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {validationDataDialog();}});

        logButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {logPredictions();}});

        /***
        runButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {executeTasks();}});
         */

        infoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {showInfo();}});

    } // setupUI end


    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        String itemSelected = parent.getItemAtPosition(position).toString();
        // SPINNER SELECT LANGUAGE;
        if (itemSelected.equals("ENGLISH")) {currentLanguage = "ENGLISH";}
        if (itemSelected.equals("ESPAÑOL")) {currentLanguage = "ESPAÑOL";}

        // SPINNER SELECT INTEGRATION;
        if (itemSelected.equals("INT 5")) {SMOOTH_SIZE = 5;}
        if (itemSelected.equals("INT 4")) {SMOOTH_SIZE = 4;}
        if (itemSelected.equals("INT 3")) {SMOOTH_SIZE = 3;}
        if (itemSelected.equals("INT 2")) {SMOOTH_SIZE = 2;}
        if (itemSelected.equals("INT 1")) {SMOOTH_SIZE = 1;}

        // SPINNER SELECT COLORMAP:
        if (itemSelected.equals("MAGMA")) {currentColorMap = "MAGMA"; }
        if (itemSelected.equals("PLASMA")) {currentColorMap = "PLASMA"; }
        if (itemSelected.equals("VIRIDIS")) {currentColorMap = "VIRIDIS"; }

        updateStrings();
        initializePredictions();

        Toast.makeText(this, "Settings Updated", Toast.LENGTH_SHORT).show();
        Log.d(LOG_TAG, "UI: Spinners selected: "+itemSelected);
    } // onItemSelected method end


    @Override
    public void onNothingSelected(AdapterView<?> parent) {
        Log.d(LOG_TAG, "Nothing Selected");
    } // onNothingSelected end


    private void updateStrings(){
        if (currentLanguage.equals("ENGLISH")) {
            setAppLocale("en");
            labels = Arrays.asList(getResources().getStringArray(R.array.g_EN));}
        if (currentLanguage.equals("ESPAÑOL")) {
            setAppLocale("es");
            labels = Arrays.asList(getResources().getStringArray(R.array.g_ES));}
        numClasses = labels.size();
        labelsArray = new String[numClasses];
        for (int i = 0; i < numClasses; i++) {
            labelsArray[i] = labels.get(i);
            Log.d("Labels", "LABEL: " + i + " " + labelsArray[i]); }
        bufferTextView.setText(R.string.textBuffer);
        xLabelTextView.setText(R.string.textXLabel);
        integrationTextView.setText(R.string.textIntegration);
        runButton.setText(R.string.textRun);
        languageTextView.setText(R.string.textLanguage);
        colorMapTextView.setText(R.string.textColorMap);
        dynamicRangeTextView.setText(getResources().getString(R.string.textDynamicRange));
    } // setLanguage end


    private void resetUI() {
        logButton.setEnabled(false);
        validateButton.setEnabled(false);
        infoButton.setEnabled(false);
        compressorSwitch.setEnabled(false);
        validateButton.setVisibility(View.INVISIBLE);
        logButton.setVisibility(View.INVISIBLE);
        infoButton.setVisibility(View.INVISIBLE);
        xLabelTextView.setVisibility(View.INVISIBLE);
        predictionView_0.setVisibility(View.INVISIBLE);
        predictionView_1.setVisibility(View.INVISIBLE);
        predictionView_2.setVisibility(View.INVISIBLE);
        predictionBar1.setVisibility(View.INVISIBLE);
        predictionBar2.setVisibility(View.INVISIBLE);
        predictionBar3.setVisibility(View.INVISIBLE);
        bufferTextView.setVisibility(View.INVISIBLE);
        bufferEditTextView.setVisibility(View.INVISIBLE);
        spectrogramImageView.setVisibility(View.INVISIBLE);
        colorBarImageView.setVisibility(View.INVISIBLE);
        loadingBar.setVisibility(View.INVISIBLE);
        dynamicRangeTextViewEdit.setVisibility(View.INVISIBLE);
        dynamicRangeTextView.setVisibility(View.INVISIBLE);
        loopSwitch.setVisibility(View.INVISIBLE);
        compressorSwitch.setVisibility(View.INVISIBLE);
    }


    private void showUI(){
        logButton.setVisibility(View.VISIBLE);
        infoButton.setVisibility(View.VISIBLE);
        predictionView_0.setVisibility(View.VISIBLE);
        predictionView_1.setVisibility(View.VISIBLE);
        predictionView_2.setVisibility(View.VISIBLE);
        bufferTextView.setVisibility(View.VISIBLE);
        bufferEditTextView.setVisibility(View.VISIBLE);
        xLabelTextView.setVisibility(View.VISIBLE);
        spectrogramImageView.setVisibility(View.VISIBLE);
        colorBarImageView.setVisibility(View.VISIBLE);
        predictionBar1.setVisibility(View.VISIBLE);
        predictionBar2.setVisibility(View.VISIBLE);
        predictionBar3.setVisibility(View.VISIBLE);
        dynamicRangeTextViewEdit.setVisibility(View.VISIBLE);
        dynamicRangeTextView.setVisibility(View.VISIBLE);
        runButton.setTextColor(getColor(R.color.colorAccent));
        logButton.setTextColor(getColor(R.color.colorAccent));
        infoButton.setTextColor(getColor(R.color.colorAccent));
        loopSwitch.setVisibility(View.VISIBLE);
        loadingBar.setVisibility(View.INVISIBLE);
        runButton.setEnabled(true);
        logButton.setEnabled(true);
        infoButton.setEnabled(true);
        languageSpinner.setEnabled(true);
        integrationSpinner.setEnabled(true);
        validateButton.setTextColor(getColor(R.color.colorAccent));
        runButton.setText(R.string.textRun);
        runButton.setTextColor(getColor(R.color.colorAccent));
        horizontalProgressBar.setProgress(0);
    } // showUI end


    private void showDeveloperUI(){
        validateButton.setEnabled(true);
        compressorSwitch.setEnabled(true);
        compressorSwitch.setVisibility(View.VISIBLE);
        validateButton.setVisibility(View.VISIBLE);
    } // showDeveloperUI end


    private void initializePredictions(){
        if (pred_0 == null || pred_0.length!=numClasses) {pred_0 = new float[numClasses];}
        if (pred_1 == null || pred_1.length!=numClasses) {pred_1 = new float[numClasses];}
        if (pred_2 == null || pred_2.length!=numClasses) {pred_2 = new float[numClasses];}
        if (pred_3 == null || pred_3.length!=numClasses) {pred_3 = new float[numClasses];}
        if (pred_4 == null || pred_4.length!=numClasses) {pred_4 = new float[numClasses];}
    }


    private void initializeLogger(){
        File folder = new File(Environment.getExternalStorageDirectory(),"SceneClassifier");
        if (folder.exists()) {  Log.d(LOG_TAG, "Directory Found");}
        else {  boolean created = folder.mkdirs();
            if (created) {    Log.d(LOG_TAG, "Directory Created");}
            else { Log.d(LOG_TAG, "Directory Not Created");}
        }
        File loggerFile = new File(directory,"Logs.csv");
        if (loggerFile.exists()) {
            Log.d(LOG_TAG, "Log File Found");
        } else {
            StringBuffer logLabelsCSV;
            logLabelsCSV = new StringBuffer("Timestamp");
            for (int i = 0; i < numClasses; i++) {
                logLabelsCSV.append(",");
                logLabelsCSV.append(labelsArray[i]);
            }
            saveCSV(logLabelsCSV, "Logs.csv");
            Log.d(LOG_TAG, "Log File has been created");
        }
    } // initializeLogger end


    private void init_model() {
        try { tflite = new Interpreter(loadModelFile());}
        catch (IOException e) { e.printStackTrace(); }
    } // init_model end


    private void showInfo(){
        Intent intent = new Intent(MainActivity.this,InformationActivity.class);
        intent.putExtra("language",currentLanguage);
        intent.putExtra("classes", numClasses+"°");
        startActivity(intent);
    }


    private void showSpectrogram() {
        double[][] spectrogramToPlot = new double[FRAMES][MEL_BINS];
        // publish min and max values.
        float specMax=-100;
        float specMin=100;
        for (int x = 0; x< FRAMES; x++ ) {
            for (int y = 0; y< MEL_BINS; y++){
                spectrogramToPlot[y][x] = spectrogram[x][y];
                if (spectrogram[x][y]>specMax){specMax = spectrogram[x][y];}
                if (spectrogram[x][y]<specMin){specMin = spectrogram[x][y];}}}
          dynamicRange = (Math.round((specMax-specMin)*80.0*10.0)/10.0)+" dB";
        // apply color map.
        SpectrogramView spectrogramView = new SpectrogramView(
                getApplicationContext(),spectrogramToPlot,currentColorMap,3,10);
        spectrogramImageView.setImageBitmap(spectrogramView.getBitmap());
        if (currentColorMap.equals("MAGMA")){
            colorBarImageView.setImageResource(R.drawable.magma_cmap);}
        if (currentColorMap.equals("PLASMA")){
            colorBarImageView.setImageResource(R.drawable.plasma_cmap);}
        if (currentColorMap.equals("VIRIDIS")){
            colorBarImageView.setImageResource(R.drawable.viridis_cmap);}
        dynamicRangeTextViewEdit.setText(dynamicRange);
        dynamicRangeTextView.bringToFront();
        dynamicRangeTextViewEdit.bringToFront();
    } // end


    private MappedByteBuffer loadModelFile() throws IOException {
        AssetFileDescriptor fileDescriptor = this.getAssets().openFd(modelFileName);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY,startOffset,declaredLength);
    } // loadModelFile method end


    private void savePredictions(float[][] outputTensorArray){
        if (pred_0 ==null || pred_0.length!=numClasses || SMOOTH_SIZE <2) {
            pred_0 = new float[numClasses];}
        if (pred_1 ==null || pred_1.length!=numClasses || SMOOTH_SIZE <3) {
            pred_1 = new float[numClasses];}
        if (pred_2 ==null || pred_2.length!=numClasses || SMOOTH_SIZE <4) {
            pred_2 = new float[numClasses];}
        if (pred_3 ==null || pred_3.length!=numClasses || SMOOTH_SIZE <5) {
            pred_3 = new float[numClasses];}
        pred_4 = new float[numClasses];
        pred_4 = pred_3.clone();
        pred_3 = pred_2.clone();
        pred_2 = pred_1.clone();
        pred_1 = pred_0.clone();
        pred_0 = outputTensorArray[0].clone();
        Log.d("NNA:", "Predictions Saved");
    }


    private void updatePredictions(){
        float[] meanPredictions = new float[numClasses];
        for (int i = 0 ; i<numClasses; i++ ){
            meanPredictions[i] = (pred_0[i]+ pred_1[i]+ pred_2[i]+ pred_3[i]+ pred_4[i])/ SMOOTH_SIZE;}
        float[] bestPredictions = meanPredictions.clone();
        Arrays.sort(bestPredictions); // sort ascending
        int[] topLabelIndexes = new int[3];
        for (int i=0; i<numClasses;i++){
            if (meanPredictions[i]==bestPredictions[numClasses-1]){topLabelIndexes[0]= i;}
            if (meanPredictions[i]==bestPredictions[numClasses-2]){topLabelIndexes[1]= i;}
            if (meanPredictions[i]==bestPredictions[numClasses-3]){topLabelIndexes[2]= i;}
        }
        label_0 = labelsArray[topLabelIndexes[0]];
        label_1 = labelsArray[topLabelIndexes[1]];
        label_2 = labelsArray[topLabelIndexes[2]];
        accuracy_0 = (int)(bestPredictions[numClasses-1]*100);
        accuracy_1 = (int)(bestPredictions[numClasses-2]*100);
        accuracy_2 = (int)(bestPredictions[numClasses-3]*100);
        // PASS RESULTS TO UI OBJECTS
        String predictionText_0 = label_0 + " (" + accuracy_0 + "%)";
        String predictionText_1 = label_1 + " (" + accuracy_1 + "%)";
        String predictionText_2 = label_2 + " (" + accuracy_2 + "%)";
        String bufferText      = sizeInFloats +" "+ getResources().getString(R.string.textSamples);
        predictionView_0.setText(predictionText_0);
        predictionView_1.setText(predictionText_1);
        predictionView_2.setText(predictionText_2);
        bufferEditTextView.setText(bufferText);
        predictionBar1.setProgress(accuracy_0);
        predictionBar2.setProgress(accuracy_1);
        predictionBar3.setProgress(accuracy_2);
    } //updatePredictions end


    private void logPredictions(){
        StringBuffer predictionLogCSV;
        predictionLogCSV = new StringBuffer("\n");
        predictionLogCSV.append(getCurrentTime());
        for (int i = 0; i< numClasses; i++){
            predictionLogCSV.append(",");
            predictionLogCSV.append(pred_0[i]);}
        saveCSV(predictionLogCSV,"Logs.csv");
        logButton.setEnabled(false);
        logButton.setTextColor(getColor(R.color.colorGray));
        Log.d(LOG_TAG, "Predictions Logged to File");
    } // logPredictions end


    private String getCurrentTime(){
        return new SimpleDateFormat("HHmmss", getDefault())
                .format(Calendar.getInstance().getTime());
    } // getCurrentTime end


    private void requestAudioPermission(){
        if (ActivityCompat.shouldShowRequestPermissionRationale(
                this,Manifest.permission.RECORD_AUDIO)) {
            new AlertDialog.Builder(this)
                    .setTitle("Permission needed")
                    .setMessage("Permission required in order to predict acoustic scenes")
                    .setPositiveButton("ok", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            ActivityCompat.requestPermissions(MainActivity.this,new String[]{
                                            Manifest.permission.RECORD_AUDIO},RECORD_AUDIO_CODE);}})
                    .setNegativeButton("cancel", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();}}).create().show();} 
                        else {ActivityCompat.requestPermissions(this,new String[] {
                                Manifest.permission.RECORD_AUDIO}, RECORD_AUDIO_CODE);} 
    } // requestPermission end


    private void automaticCheckAudioPermission(){
        if (ContextCompat.checkSelfPermission(MainActivity.this,
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording();
            Toast.makeText(MainActivity.this, "Microphone Access: OK!",
                    Toast.LENGTH_LONG).show(); }
        else {requestAudioPermission();}
        Log.d(LOG_TAG, "UI: Recording Permissions Checked");
    } // automaticCheckPermission end


    private void automaticCheckStoragePermission(){
        if (ContextCompat.checkSelfPermission(MainActivity.this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(MainActivity.this, "Storage Access: OK!",
                    Toast.LENGTH_SHORT).show(); }
        else {requestStoragePermission();}
        Log.d(LOG_TAG, "UI: Recording Permissions Checked");
    } // automaticCheckPermission end


    private void requestStoragePermission(){
        if (ActivityCompat.shouldShowRequestPermissionRationale(
                this,Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            new AlertDialog.Builder(this)
                    .setTitle("Permission needed")
                    .setMessage("Permission required in order to save data")
                    .setPositiveButton("ok", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            ActivityCompat.requestPermissions(MainActivity.this,new String[]{
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE},STORAGE_CODE);}})
                    .setNegativeButton("cancel", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();}}).create().show();}
        else {ActivityCompat.requestPermissions(this,new String[] {
                Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_CODE);}
    } // requestStoragePermission end


    private boolean isExternalStorageWritable(){
        if(Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())){
            return true;}
        else { Log.d(LOG_TAG, "External Storage is not Writable"); return false; }
    } // isExternalStorageWritable end


    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == RECORD_AUDIO_CODE)  {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission GRANTED", Toast.LENGTH_SHORT).show();
                stopRecording();
                startRecording();
                automaticCheckStoragePermission();
            } else {
                Toast.makeText(this, "Permission DENIED", Toast.LENGTH_SHORT).show();}}
    } // onRequestPermissionsResult end


    private void validationDataDialog(){
        saveName = "NewFile";
        AlertDialog.Builder alertBuilder = new AlertDialog.Builder(MainActivity.this);
        View dialogView = getLayoutInflater().inflate(R.layout.input_dialog,null);
        final EditText userInputText = dialogView.findViewById(R.id.userInputText);
        alertBuilder.setView(dialogView);
        alertBuilder.setCancelable(true)
                .setTitle("Validation Data Name")
                .setPositiveButton("OK",new DialogInterface.OnClickListener(){
                    @Override
                    public void onClick(DialogInterface dialog,int which){
                        if(!userInputText.getText().toString().isEmpty()) {
                            dialog.dismiss();
                            saveName = "_" + userInputText.getText().toString();
                            saveValidationData();
                        }}});
        Dialog dialog = alertBuilder.create();
        dialog.show();
    }


    private void saveValidationData(){
        // INITIALIZE OBJECTS:
        StringBuffer signalDataCSV;
        StringBuffer spectrogramDataCSV;
        StringBuffer predictionsDataCSV;
        // INITIALIZE LABELS:
        signalDataCSV = new StringBuffer("index" + "," + "Amplitude");
        spectrogramDataCSV = new StringBuffer();
        predictionsDataCSV = new StringBuffer("index" + "," + "class" + "," + "output");
        // BUILD SIGNAL STRING BUFFER:
        for (int i = 0; i< RECORDING_LENGTH; i++){
            signalDataCSV.append("\n");
            signalDataCSV.append(i);
            signalDataCSV.append("," );
            signalDataCSV.append(inputBuffer32[i]);}
        // BUILD SPECTROGRAM STRING BUFFER:
        for (int i = 0; i< MEL_BINS; i++){
            for (int j = 0; j< FRAMES; j++){
                spectrogramDataCSV.append(spectrogram[i][j]);
                spectrogramDataCSV.append(",");}
            spectrogramDataCSV.append("\n");}
        // BUILD PREDICTIONS STRING BUFFER:
        for (int i = 0; i< numClasses;i++){
            predictionsDataCSV.append("\n");
            predictionsDataCSV.append(i);
            predictionsDataCSV.append(",");
            predictionsDataCSV.append(labelsArray[i]);
            predictionsDataCSV.append(",");
            predictionsDataCSV.append(pred_0[i]);}
        // SAVE CSV FILES:
        saveCSV(signalDataCSV,getCurrentTime()+saveName+"_signal.csv");
        saveCSV(spectrogramDataCSV,getCurrentTime()+saveName+"_spectrogram.csv");
        saveCSV(predictionsDataCSV,getCurrentTime()+saveName+"_predictions.csv");
        validateButton.setTextColor(getColor(R.color.colorGray));
        Log.d(LOG_TAG, "CSV Files Saved to Memory");
    } // saveValidationData end


    private void saveCSV(StringBuffer stringBufferData, String fileName ){
        if (isExternalStorageWritable()) {
            File currentFile = new File(directory, fileName);
            String data = stringBufferData.toString(); // convert StringBuffer to String
            try {
                FileOutputStream fos = new FileOutputStream(currentFile,true);
                fos.write(data.getBytes());
                fos.close();
                Toast.makeText(this,fileName+" "+
                        getResources().getString(R.string.textSaved),Toast.LENGTH_SHORT).show();
            } catch (IOException e) { e.printStackTrace();
            }} else {Toast.makeText(this,fileName+" "+
                        getResources().getString(R.string.textNot_saved),Toast.LENGTH_SHORT).show();
        }
    } // saveCSV end

} // MainActivity class end;
