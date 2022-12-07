/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mobileer.oboetester;

import static com.mobileer.oboetester.IntentBasedTestSupport.configureStreamsFromBundle;

import android.app.Activity;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.util.Log;
import android.widget.CheckBox;

import com.mobileer.audio_device.AudioDeviceInfoConverter;

import java.lang.reflect.Field;

/**
 * Play a recognizable tone on each channel of each speaker device
 * and listen for the result through a microphone.
 * Also test each microphone channel and device.
 * Try each InputPreset.
 *
 * The analysis is based on a cosine transform of a single
 * frequency. The magnitude indicates the level.
 * The variations in phase, "jitter" indicate how noisy the
 * signal is or whether it is corrupted. A noisy room may have
 * energy at the target frequency but the phase will be random.
 *
 * This test requires a quiet room but no other hardware.
 */
public class TestDataPathsActivity  extends BaseAutoGlitchActivity {

    public static final String KEY_USE_INPUT_PRESETS = "use_input_presets";
    public static final boolean VALUE_DEFAULT_USE_INPUT_PRESETS = true;

    public static final String KEY_USE_INPUT_DEVICES = "use_input_devices";
    public static final boolean VALUE_DEFAULT_USE_INPUT_DEVICES = true;

    public static final String KEY_USE_OUTPUT_DEVICES = "use_output_devices";
    public static final boolean VALUE_DEFAULT_USE_OUTPUT_DEVICES = true;

    public static final String KEY_SINGLE_TEST_INDEX = "single_test_index";
    public static final int VALUE_DEFAULT_SINGLE_TEST_INDEX = -1;

    public static final int DURATION_SECONDS = 3;
    private final static double MIN_REQUIRED_MAGNITUDE = 0.001;
    private final static double MAX_SINE_FREQUENCY = 1000.0;
    private final static int TYPICAL_SAMPLE_RATE = 48000;
    private final static double FRAMES_PER_CYCLE = TYPICAL_SAMPLE_RATE / MAX_SINE_FREQUENCY;
    private final static double PHASE_PER_BIN = 2.0 * Math.PI / FRAMES_PER_CYCLE;
    private final static double MAX_ALLOWED_JITTER = 2.0 * PHASE_PER_BIN;
    private final static String MAGNITUDE_FORMAT = "%7.5f";

    final int TYPE_BUILTIN_SPEAKER_SAFE = 0x18; // API 30

    private double mMagnitude;
    private double mMaxMagnitude;
    private int    mPhaseCount;
    private double mPhase;
    private double mPhaseErrorSum;
    private double mPhaseErrorCount;

    AudioManager   mAudioManager;
    private CheckBox mCheckBoxInputPresets;
    private CheckBox mCheckBoxInputDevices;
    private CheckBox mCheckBoxOutputDevices;

    private static final int[] INPUT_PRESETS = {
            // VOICE_RECOGNITION gets tested in testInputs()
            // StreamConfiguration.INPUT_PRESET_VOICE_RECOGNITION,
            StreamConfiguration.INPUT_PRESET_GENERIC,
            StreamConfiguration.INPUT_PRESET_CAMCORDER,
            // TODO Resolve issue with echo cancellation killing the signal.
            StreamConfiguration.INPUT_PRESET_VOICE_COMMUNICATION,
            StreamConfiguration.INPUT_PRESET_UNPROCESSED,
            StreamConfiguration.INPUT_PRESET_VOICE_PERFORMANCE,
    };

    @NonNull
    public static String comparePassedField(String prefix, Object failed, Object passed, String name) {
        try {
            Field field = failed.getClass().getField(name);
            int failedValue = field.getInt(failed);
            int passedValue = field.getInt(passed);
            return (failedValue == passedValue) ? ""
                :  (prefix + " " + name + ": passed = " + passedValue + ", failed = " + failedValue + "\n");
        } catch (NoSuchFieldException e) {
            return "ERROR - no such field  " + name;
        } catch (IllegalAccessException e) {
            return "ERROR - cannot access  " + name;
        }
    }

    public static double calculatePhaseError(double p1, double p2) {
        double diff = Math.abs(p1 - p2);
        if (diff > Math.PI) {
            diff = (Math.PI * 2) - diff;
        }
        return diff;
    }

    // Periodically query for magnitude and phase from the native detector.
    protected class DataPathSniffer extends NativeSniffer {

        public DataPathSniffer(Activity activity) {
            super(activity);
        }

        @Override
        public void startSniffer() {
            mMagnitude = -1.0;
            mMaxMagnitude = -1.0;
            mPhaseCount = 0;
            mPhase = 0.0;
            mPhaseErrorSum = 0.0;
            mPhaseErrorCount = 0;
            super.startSniffer();
        }

        @Override
        public void run() {
            mMagnitude = getMagnitude();
            mMaxMagnitude = getMaxMagnitude();
            Log.d(TAG, String.format("magnitude = %7.4f, maxMagnitude = %7.4f",
                    mMagnitude, mMaxMagnitude));
            // Only look at the phase if we have a signal.
            if (mMagnitude >= MIN_REQUIRED_MAGNITUDE) {
                double phase = getPhase();
                if (mPhaseCount > 3) {
                    double phaseError = calculatePhaseError(phase, mPhase);
                    // low pass filter
                    mPhaseErrorSum += phaseError;
                    mPhaseErrorCount++;
                    Log.d(TAG, String.format("phase = %7.4f, diff = %7.4f, jitter = %7.4f",
                            phase, phaseError, getAveragePhaseError()));
                }
                mPhase = phase;
                mPhaseCount++;
            }
            reschedule();
        }

        public String getCurrentStatusReport() {
            StringBuffer message = new StringBuffer();
            message.append(
                    "magnitude = " + getMagnitudeText(mMagnitude)
                    + ", max = " + getMagnitudeText(mMaxMagnitude)
                    + "\nphase = " + getMagnitudeText(mPhase)
                    + ", jitter = " + getMagnitudeText(getAveragePhaseError())
                    + ", #" + mPhaseCount
                    + "\n");
            return message.toString();
        }

        @Override
        public String getShortReport() {
            return "maxMag = " + getMagnitudeText(mMaxMagnitude)
                    + ", jitter = " + (isPhaseJitterValid() ? getMagnitudeText(getAveragePhaseError()) : "?");
        }

        @Override
        public void updateStatusText() {
            mLastGlitchReport = getCurrentStatusReport();
            runOnUiThread(() -> {
                setAnalyzerText(mLastGlitchReport);
            });
        }
    }

    @Override
    NativeSniffer createNativeSniffer() {
        return new TestDataPathsActivity.DataPathSniffer(this);
    }

    native double getMagnitude();
    native double getMaxMagnitude();
    native double getPhase();

    @Override
    protected void inflateActivity() {
        setContentView(R.layout.activity_data_paths);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mCheckBoxInputPresets = (CheckBox)findViewById(R.id.checkbox_paths_input_presets);
        mCheckBoxInputDevices = (CheckBox)findViewById(R.id.checkbox_paths_input_devices);
        mCheckBoxOutputDevices = (CheckBox)findViewById(R.id.checkbox_paths_output_devices);
    }

    @Override
    public String getTestName() {
        return "DataPaths";
    }

    @Override
    int getActivityType() {
        return ACTIVITY_DATA_PATHS;
    }

    static String getMagnitudeText(double value) {
        return String.format(MAGNITUDE_FORMAT, value);
    }

    protected String getConfigText(StreamConfiguration config) {
        String text = super.getConfigText(config);
        if (config.getDirection() == StreamConfiguration.DIRECTION_INPUT) {
            text += ", inPre = " + StreamConfiguration.convertInputPresetToText(config.getInputPreset());
        }
        return text;
    }

    @Override
    protected String shouldTestBeSkipped() {
        String why = "";
        StreamConfiguration requestedInConfig = mAudioInputTester.requestedConfiguration;
        StreamConfiguration requestedOutConfig = mAudioOutTester.requestedConfiguration;
        StreamConfiguration actualInConfig = mAudioInputTester.actualConfiguration;
        StreamConfiguration actualOutConfig = mAudioOutTester.actualConfiguration;
        // No point running the test if we don't get the data path we requested.
        if (actualInConfig.isMMap() != requestedInConfig.isMMap()) {
            log("Did not get requested MMap input stream");
            why += "mmap";
        }
        if (actualOutConfig.isMMap() != requestedOutConfig.isMMap()) {
            log("Did not get requested MMap output stream");
            why += "mmap";
        }
        // Did we request a device and not get that device?
        if (requestedInConfig.getDeviceId() != 0
                && (requestedInConfig.getDeviceId() != actualInConfig.getDeviceId())) {
            why += ", inDev(" + requestedInConfig.getDeviceId()
                    + "!=" + actualInConfig.getDeviceId() + ")";
        }
        if (requestedOutConfig.getDeviceId() != 0
                && (requestedOutConfig.getDeviceId() != actualOutConfig.getDeviceId())) {
            why += ", outDev(" + requestedOutConfig.getDeviceId()
                    + "!=" + actualOutConfig.getDeviceId() + ")";
        }
        if ((requestedInConfig.getInputPreset() != actualInConfig.getInputPreset())) {
            why += ", inPre(" + requestedInConfig.getInputPreset()
                    + "!=" + actualInConfig.getInputPreset() + ")";
        }
        return why;
    }

    @Override
    protected boolean isFinishedEarly() {
        return (mMaxMagnitude > MIN_REQUIRED_MAGNITUDE)
                && (getAveragePhaseError() < MAX_ALLOWED_JITTER)
                && isPhaseJitterValid();
    }

    // @return reasons for failure of empty string
    @Override
    public String didTestFail() {
        String why = "";
        StreamConfiguration requestedInConfig = mAudioInputTester.requestedConfiguration;
        StreamConfiguration requestedOutConfig = mAudioOutTester.requestedConfiguration;
        StreamConfiguration actualInConfig = mAudioInputTester.actualConfiguration;
        StreamConfiguration actualOutConfig = mAudioOutTester.actualConfiguration;
        if (mMaxMagnitude <= MIN_REQUIRED_MAGNITUDE) {
            why += ", mag";
        }
        if (!isPhaseJitterValid()) {
            why += ", jitterUnknown";
        } else if (getAveragePhaseError() > MAX_ALLOWED_JITTER) {
            why += ", jitterHigh";
        }
        return why;
    }

    private double getAveragePhaseError() {
        return (mPhaseErrorCount > 0) ? (mPhaseErrorSum / mPhaseErrorCount) : MAX_ALLOWED_JITTER;
    }

    private boolean isPhaseJitterValid() {
        return mPhaseErrorCount > 4;
    }

    String getOneLineSummary() {
        StreamConfiguration actualInConfig = mAudioInputTester.actualConfiguration;
        StreamConfiguration actualOutConfig = mAudioOutTester.actualConfiguration;
        return "#" + mAutomatedTestRunner.getTestCount()
                + ", IN" + (actualInConfig.isMMap() ? "-M" : "-L")
                + " D=" + actualInConfig.getDeviceId()
                + ", ch=" + channelText(getInputChannel(), actualInConfig.getChannelCount())
                + ", OUT" + (actualOutConfig.isMMap() ? "-M" : "-L")
                + " D=" + actualOutConfig.getDeviceId()
                + ", ch=" + channelText(getOutputChannel(), actualOutConfig.getChannelCount())
                + ", mag = " + getMagnitudeText(mMaxMagnitude);
    }

    void setupDeviceCombo(int numInputChannels,
                          int inputChannel,
                          int numOutputChannels,
                          int outputChannel) throws InterruptedException {
        // Configure settings
        StreamConfiguration requestedInConfig = mAudioInputTester.requestedConfiguration;
        StreamConfiguration requestedOutConfig = mAudioOutTester.requestedConfiguration;

        requestedInConfig.reset();
        requestedOutConfig.reset();

        requestedInConfig.setPerformanceMode(StreamConfiguration.PERFORMANCE_MODE_LOW_LATENCY);
        requestedOutConfig.setPerformanceMode(StreamConfiguration.PERFORMANCE_MODE_LOW_LATENCY);

        requestedInConfig.setSharingMode(StreamConfiguration.SHARING_MODE_SHARED);
        requestedOutConfig.setSharingMode(StreamConfiguration.SHARING_MODE_SHARED);

        requestedInConfig.setChannelCount(numInputChannels);
        requestedOutConfig.setChannelCount(numOutputChannels);

        requestedInConfig.setMMap(false);
        requestedOutConfig.setMMap(false);

        setInputChannel(inputChannel);
        setOutputChannel(outputChannel);
    }

    private TestResult testConfigurationsAddMagJitter() throws InterruptedException {
        TestResult testResult = testConfigurations();
        if (testResult != null) {
            testResult.addComment("mag = " + TestDataPathsActivity.getMagnitudeText(mMagnitude)
                    + ", jitter = " + TestDataPathsActivity.getMagnitudeText(getAveragePhaseError()));
        }
        return testResult;
    }

    void testPresetCombo(int inputPreset,
                         int numInputChannels,
                         int inputChannel,
                         int numOutputChannels,
                         int outputChannel,
                         boolean mmapEnabled
                   ) throws InterruptedException {
        setupDeviceCombo(numInputChannels, inputChannel, numOutputChannels, outputChannel);

        StreamConfiguration requestedInConfig = mAudioInputTester.requestedConfiguration;
        requestedInConfig.setInputPreset(inputPreset);
        requestedInConfig.setMMap(mmapEnabled);

        mMagnitude = -1.0;
        TestResult testResult = testConfigurationsAddMagJitter();
        if (testResult != null) {
            int result = testResult.result;
            String summary = getOneLineSummary()
                    + ", inPre = "
                    + StreamConfiguration.convertInputPresetToText(inputPreset)
                    + "\n";
            appendSummary(summary);
            if (result == TEST_RESULT_FAILED) {
                if (getMagnitude() < 0.000001) {
                    testResult.addComment("The input is completely SILENT!");
                } else if (inputPreset == StreamConfiguration.INPUT_PRESET_VOICE_COMMUNICATION) {
                    testResult.addComment("Maybe sine wave blocked by Echo Cancellation!");
                }
            }
        }
    }

    void testPresetCombo(int inputPreset,
                         int numInputChannels,
                         int inputChannel,
                         int numOutputChannels,
                         int outputChannel
    ) throws InterruptedException {
        if (NativeEngine.isMMapSupported()) {
            testPresetCombo(inputPreset, numInputChannels, inputChannel,
                    numOutputChannels, outputChannel, true);
        }
        testPresetCombo(inputPreset, numInputChannels, inputChannel,
                numOutputChannels, outputChannel, false);
    }

    void testPresetCombo(int inputPreset) throws InterruptedException {
        setTestName("Test InPreset = " + StreamConfiguration.convertInputPresetToText(inputPreset));
        testPresetCombo(inputPreset, 1, 0, 1, 0);
    }

    private void testInputPresets() throws InterruptedException {
        logBoth("\nTest InputPreset -------");

        for (int inputPreset : INPUT_PRESETS) {
            testPresetCombo(inputPreset);
        }
// TODO Resolve issue with echo cancellation killing the signal.
//        testPresetCombo(StreamConfiguration.INPUT_PRESET_VOICE_COMMUNICATION,
//                1, 0, 2, 0);
//        testPresetCombo(StreamConfiguration.INPUT_PRESET_VOICE_COMMUNICATION,
//                1, 0, 2, 1);
//        testPresetCombo(StreamConfiguration.INPUT_PRESET_VOICE_COMMUNICATION,
//                2, 0, 2, 0);
//        testPresetCombo(StreamConfiguration.INPUT_PRESET_VOICE_COMMUNICATION,
//                2, 0, 2, 1);
    }

    void testInputDeviceCombo(int deviceId,
                              int numInputChannels,
                              int inputChannel,
                              boolean mmapEnabled) throws InterruptedException {
        final int numOutputChannels = 2;
        setupDeviceCombo(numInputChannels, inputChannel, numOutputChannels, 0);

        StreamConfiguration requestedInConfig = mAudioInputTester.requestedConfiguration;
        requestedInConfig.setInputPreset(StreamConfiguration.INPUT_PRESET_VOICE_RECOGNITION);
        requestedInConfig.setDeviceId(deviceId);
        requestedInConfig.setMMap(mmapEnabled);

        mMagnitude = -1.0;
        TestResult testResult = testConfigurationsAddMagJitter();
        if (testResult != null) {
            appendSummary(getOneLineSummary() + "\n");
        }
    }

    void testInputDeviceCombo(int deviceId,
                              int deviceType,
                              int numInputChannels,
                              int inputChannel) throws InterruptedException {

        String typeString = AudioDeviceInfoConverter.typeToString(deviceType);
        setTestName("Test InDev: #" + deviceId + " " + typeString
                + "_" + inputChannel + "/" + numInputChannels);
        if (NativeEngine.isMMapSupported()) {
            testInputDeviceCombo(deviceId, numInputChannels, inputChannel, true);
        }
        testInputDeviceCombo(deviceId, numInputChannels, inputChannel, false);
    }

    void testInputDevices() throws InterruptedException {
        logBoth("\nTest Input Devices -------");

        AudioDeviceInfo[] devices = mAudioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        int numTested = 0;
        for (AudioDeviceInfo deviceInfo : devices) {
            log("----\n"
                    + AudioDeviceInfoConverter.toString(deviceInfo) + "\n");
            if (!deviceInfo.isSource()) continue; // FIXME log as error?!
            int deviceType = deviceInfo.getType();
            if (deviceType == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                int id = deviceInfo.getId();
                int[] channelCounts = deviceInfo.getChannelCounts();
                numTested++;
                // Always test mono and stereo.
                testInputDeviceCombo(id, deviceType, 1, 0);
                testInputDeviceCombo(id, deviceType, 2, 0);
                testInputDeviceCombo(id, deviceType, 2, 1);
                if (channelCounts.length > 0) {
                    for (int numChannels : channelCounts) {
                        // Test higher channel counts.
                        if (numChannels > 2) {
                            log("numChannels = " + numChannels + "\n");
                            for (int channel = 0; channel < numChannels; channel++) {
                                testInputDeviceCombo(id, deviceType, numChannels, channel);
                            }
                        }
                    }
                }
            } else {
                log("Device skipped for type.");
            }
        }

        if (numTested == 0) {
            log("NO INPUT DEVICE FOUND!\n");
        }
    }

    void testOutputDeviceCombo(int deviceId,
                               int deviceType,
                               int numOutputChannels,
                               int outputChannel,
                               boolean mmapEnabled) throws InterruptedException {
        final int numInputChannels = 2; // TODO review, done because of mono problems on some devices
        setupDeviceCombo(numInputChannels, 0, numOutputChannels, outputChannel);

        StreamConfiguration requestedOutConfig = mAudioOutTester.requestedConfiguration;
        requestedOutConfig.setDeviceId(deviceId);
        requestedOutConfig.setMMap(mmapEnabled);

        mMagnitude = -1.0;
        TestResult testResult = testConfigurationsAddMagJitter();
        if (testResult != null) {
            int result = testResult.result;
            appendSummary(getOneLineSummary() + "\n");
            if (result == TEST_RESULT_FAILED) {
                if (deviceType == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                        && numOutputChannels == 2
                        && outputChannel == 1) {
                    testResult.addComment("Maybe EARPIECE does not mix stereo to mono!");
                }
                if (deviceType == TYPE_BUILTIN_SPEAKER_SAFE
                        && numOutputChannels == 2
                        && outputChannel == 0) {
                    testResult.addComment("Maybe SPEAKER_SAFE dropped channel zero!");
                }
            }
        }
    }

    void testOutputDeviceCombo(int deviceId,
                               int deviceType,
                               int numOutputChannels,
                               int outputChannel) throws InterruptedException {
        String typeString = AudioDeviceInfoConverter.typeToString(deviceType);
        setTestName("Test OutDev: #" + deviceId + " " + typeString
                + "_" + outputChannel + "/" + numOutputChannels);
        if (NativeEngine.isMMapSupported()) {
            testOutputDeviceCombo(deviceId, deviceType, numOutputChannels, outputChannel, true);
        }
        testOutputDeviceCombo(deviceId, deviceType, numOutputChannels, outputChannel, false);
    }

    void logBoth(String text) {
        log(text);
        appendSummary(text + "\n");
    }

    void logFailed(String text) {
        log(text);
        logAnalysis(text + "\n");
    }

    void testOutputDevices() throws InterruptedException {
        logBoth("\nTest Output Devices -------");

        AudioDeviceInfo[] devices = mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        int numTested = 0;
        for (AudioDeviceInfo deviceInfo : devices) {
            log("----\n"
                    + AudioDeviceInfoConverter.toString(deviceInfo) + "\n");
            if (!deviceInfo.isSink()) continue;
            int deviceType = deviceInfo.getType();
            if (deviceType == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                || deviceType == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                || deviceType == TYPE_BUILTIN_SPEAKER_SAFE) {
                int id = deviceInfo.getId();
                int[] channelCounts = deviceInfo.getChannelCounts();
                numTested++;
                // Always test mono and stereo.
                testOutputDeviceCombo(id, deviceType, 1, 0);
                testOutputDeviceCombo(id, deviceType, 2, 0);
                testOutputDeviceCombo(id, deviceType, 2, 1);
                if (channelCounts.length > 0) {
                    for (int numChannels : channelCounts) {
                        // Test higher channel counts.
                        if (numChannels > 2) {
                            log("numChannels = " + numChannels + "\n");
                            for (int channel = 0; channel < numChannels; channel++) {
                                testOutputDeviceCombo(id, deviceType, numChannels, channel);
                            }
                        }
                    }
                }
            } else {
                log("Device skipped for type.");
            }
        }
        if (numTested == 0) {
            log("NO OUTPUT DEVICE FOUND!\n");
        }
    }

    @Override
    public void runTest() {
        try {
            logDeviceInfo();
            log("min.required.magnitude = " + MIN_REQUIRED_MAGNITUDE);
            log("max.allowed.jitter = " + MAX_ALLOWED_JITTER);
            log("test.gap.msec = " + mGapMillis);
            
            mTestResults.clear();
            mDurationSeconds = DURATION_SECONDS;

            if (mCheckBoxInputPresets.isChecked()) {
                runOnUiThread(() -> mCheckBoxInputPresets.setEnabled(false));
                testInputPresets();
            }
            if (mCheckBoxInputDevices.isChecked()) {
                runOnUiThread(() -> mCheckBoxInputDevices.setEnabled(false));
                testInputDevices();
            }
            if (mCheckBoxOutputDevices.isChecked()) {
                runOnUiThread(() -> mCheckBoxOutputDevices.setEnabled(false));
                testOutputDevices();
            }

            analyzeTestResults();

        } catch (InterruptedException e) {
            analyzeTestResults();
        } catch (Exception e) {
            log(e.getMessage());
            showErrorToast(e.getMessage());
        } finally {
            runOnUiThread(() -> {
                mCheckBoxInputPresets.setEnabled(true);
                mCheckBoxInputDevices.setEnabled(true);
                mCheckBoxOutputDevices.setEnabled(true);
            });
        }
    }

    @Override
    public void startTestUsingBundle() {
        StreamConfiguration requestedInConfig = mAudioInputTester.requestedConfiguration;
        StreamConfiguration requestedOutConfig = mAudioOutTester.requestedConfiguration;
        configureStreamsFromBundle(mBundleFromIntent, requestedInConfig, requestedOutConfig);

        boolean shouldUseInputPresets = mBundleFromIntent.getBoolean(KEY_USE_INPUT_PRESETS,
                VALUE_DEFAULT_USE_INPUT_PRESETS);
        boolean shouldUseInputDevices = mBundleFromIntent.getBoolean(KEY_USE_INPUT_DEVICES,
                VALUE_DEFAULT_USE_INPUT_DEVICES);
        boolean shouldUseOutputDevices = mBundleFromIntent.getBoolean(KEY_USE_OUTPUT_DEVICES,
                VALUE_DEFAULT_USE_OUTPUT_DEVICES);
        int singleTestIndex = mBundleFromIntent.getInt(KEY_SINGLE_TEST_INDEX,
                VALUE_DEFAULT_SINGLE_TEST_INDEX);

        runOnUiThread(() -> {
            mCheckBoxInputPresets.setChecked(shouldUseInputPresets);
            mCheckBoxInputDevices.setChecked(shouldUseInputDevices);
            mCheckBoxOutputDevices.setChecked(shouldUseOutputDevices);
            mAutomatedTestRunner.setTestIndexText(singleTestIndex);
        });

        mAutomatedTestRunner.startTest();
    }
}
