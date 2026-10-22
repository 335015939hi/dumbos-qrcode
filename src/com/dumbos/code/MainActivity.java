/*
 * Copyright 2026
 *
 * Licensed under the Apache License, Version 2.0.
 */

package com.dumbos.code;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.system.Os;
import android.system.OsConstants;
import android.view.TextureView;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Main and only activity. */
public final class MainActivity extends Activity
        implements TextureView.SurfaceTextureListener {
    private static final int CAMERA_PERMISSION_REQUEST = 100;

    private static final String EXECUTABLE = "dumbos";

    /*
     * Add new argv[1] choices here. The Spinner is populated directly from
     * this array, so no XML resource changes are needed.
     */
    private static final String[] SUBCOMMANDS = {
            "code",
            "version",
            "ok",
            "get_name",
            "set_name",
            "oem_unlock",
            "oem_lock",
            "enable_adb",
            "disable_adb",
            "enable_wifi",
            "disable_wifi",
    };

    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor();

    private View mainPanel;
    private View scannerPanel;
    private Button scanButton;
    private Button runButton;
    private EditText codeInput;
    private Spinner commandSpinner;
    private ProgressBar commandProgress;
    private ScrollView outputScroll;
    private TextView outputText;
    private TextureView cameraPreview;

    private QrScanner qrScanner;
    private volatile boolean commandRunning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mainPanel = findViewById(R.id.main_panel);
        scannerPanel = findViewById(R.id.scanner_panel);
        scanButton = findViewById(R.id.scan_button);
        runButton = findViewById(R.id.run_button);
        codeInput = findViewById(R.id.code_input);
        commandSpinner = findViewById(R.id.command_spinner);
        commandProgress = findViewById(R.id.command_progress);
        outputScroll = findViewById(R.id.output_scroll);
        outputText = findViewById(R.id.output_text);
        cameraPreview = findViewById(R.id.camera_preview);
        Button cancelScanButton = findViewById(R.id.cancel_scan_button);

        cameraPreview.setSurfaceTextureListener(this);

        ArrayAdapter<String> commandAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                SUBCOMMANDS);
        commandAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        commandSpinner.setAdapter(commandAdapter);

        scanButton.setOnClickListener(ignored -> requestQrScan());
        runButton.setOnClickListener(ignored -> runManualInput());
        cancelScanButton.setOnClickListener(ignored -> closeScanner());
    }

    private void requestQrScan() {
        if (commandRunning) {
            Toast.makeText(this, R.string.already_running, Toast.LENGTH_SHORT).show();
            return;
        }

        if (checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            openScanner();
            return;
        }

        requestPermissions(
                new String[] {Manifest.permission.CAMERA},
                CAMERA_PERMISSION_REQUEST);
    }

    private void openScanner() {
        mainPanel.setVisibility(View.GONE);
        scannerPanel.setVisibility(View.VISIBLE);

        qrScanner = new QrScanner(this, new QrScanner.Callback() {
            @Override
            public void onQrCode(String value) {
                runOnUiThread(() -> {
                    codeInput.setText(value);
                    closeScanner();
                    runCommand(value);
                });
            }

            @Override
            public void onError(Exception error) {
                runOnUiThread(() -> {
                    closeScanner();
                    showDialog("Camera error", readableException(error));
                });
            }
        });

        if (cameraPreview.isAvailable()) {
            startScanner(
                    cameraPreview.getSurfaceTexture(),
                    cameraPreview.getWidth(),
                    cameraPreview.getHeight());
        }
    }

    private void startScanner(SurfaceTexture surface, int width, int height) {
        QrScanner scanner = qrScanner;
        if (scanner != null) {
            scanner.start(surface, width, height);
        }
    }

    private void closeScanner() {
        QrScanner scanner = qrScanner;
        qrScanner = null;

        if (scanner != null) {
            scanner.stop();
        }

        scannerPanel.setVisibility(View.GONE);
        mainPanel.setVisibility(View.VISIBLE);
    }

    private void runManualInput() {
        runCommand(codeInput.getText().toString());
    }

    private void runCommand(String code) {
        /*if (code.isEmpty()) {
            Toast.makeText(this, R.string.empty_code, Toast.LENGTH_SHORT).show();
            return;
        }*/

        if (commandRunning) {
            Toast.makeText(this, R.string.already_running, Toast.LENGTH_SHORT).show();
            return;
        }

        String subcommand = (String) commandSpinner.getSelectedItem();
        if (subcommand == null) {
            throw new IllegalStateException("No dumbos subcommand is selected");
        }

        commandRunning = true;
        setCommandUiRunning(true);
        outputText.setText("Running dumbos " + subcommand + "...\n\n");

        commandExecutor.execute(() -> executeCommand(subcommand, code));
    }

    private void executeCommand(String subcommand, String code) {
        Process process = null;

        try {
            /*
             * IMPORTANT: This list is passed as an exec-style argument vector.
             *
             * argv[0] = "dumbos"
             * argv[1] = subcommand selected in the dropdown
             * argv[2] = code
             *
             * There is intentionally no shell and no string concatenation.
             */
            ProcessBuilder builder =
                    new ProcessBuilder(EXECUTABLE, subcommand, code);

            process = builder.start();
            // dumbos receives EOF on stdin immediately.
            closeQuietly(process.getOutputStream());

            Process processForStderr = process;
            Thread stderrDrainer = new Thread(
                    () -> discard(processForStderr.getErrorStream()),
                    "dumbos-stderr-discard");
            stderrDrainer.start();

            streamStdout(process.getInputStream());

            int exitCode = process.waitFor();
            stderrDrainer.join();

            finishCommand(exitCode);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            finishCommandException("Command interrupted", error);
        } catch (IOException error) {
            finishCommandException("Could not execute dumbos", error);
        } finally {
            if (process != null) {
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
            }
        }
    }

    private void streamStdout(InputStream input) throws IOException {
        try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int count;

            while ((count = reader.read(buffer)) != -1) {
                String chunk = new String(buffer, 0, count);
                runOnUiThread(() -> appendOutput(chunk));
            }
        }
    }

    private void appendOutput(String chunk) {
        if (isDestroyed()) {
            return;
        }

        outputText.append(chunk);
        outputScroll.post(() -> outputScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void discard(InputStream input) {
        try (InputStream stream = input) {
            byte[] buffer = new byte[8192];
            while (stream.read(buffer) != -1) {
                // Deliberately discarded. Reading prevents a full stderr pipe
                // from blocking the child process.
            }
        } catch (IOException ignored) {
            // stderr is explicitly unwanted.
        }
    }

    private void finishCommand(int exitCode) {
        runOnUiThread(() -> {
            if (isDestroyed()) {
                return;
            }

            commandRunning = false;
            setCommandUiRunning(false);

            if (exitCode == 0) {
                showDialog(
                        "Success",
                        "Command completed successfully (exit status 0).");
                return;
            }

            /*
             * A process exit status is not inherently an errno. This app treats
             * it as one because that is the protocol requested for dumbos.
             */
            String errnoName = OsConstants.errnoName(exitCode);
            String errnoDescription = Os.strerror(exitCode);

            StringBuilder message = new StringBuilder();
            message.append("Command failed with exit status ")
                    .append(exitCode)
                    .append('.');

            if (errnoName != null) {
                message.append("\n\n")
                        .append(errnoName);

                if (errnoDescription != null && !errnoDescription.isEmpty()) {
                    message.append(": ").append(errnoDescription);
                }
            }

            showDialog("Error", message.toString());
        });
    }

    private void finishCommandException(String title, Exception error) {
        runOnUiThread(() -> {
            if (isDestroyed()) {
                return;
            }

            commandRunning = false;
            setCommandUiRunning(false);
            showDialog(title, readableException(error));
        });
    }

    private void setCommandUiRunning(boolean running) {
        scanButton.setEnabled(!running);
        runButton.setEnabled(!running);
        codeInput.setEnabled(!running);
        commandSpinner.setEnabled(!running);
        commandProgress.setVisibility(running ? View.VISIBLE : View.GONE);
    }

    private String readableException(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isEmpty()) {
            return error.getClass().getSimpleName();
        }
        return error.getClass().getSimpleName() + ": " + message;
    }

    private void showDialog(String title, String message) {
        if (isFinishing() || isDestroyed()) {
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
        }
    }

    private void closeQuietly(OutputStream output) {
        if (output == null) {
            return;
        }
        try {
            output.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void onSurfaceTextureAvailable(
            SurfaceTexture surface, int width, int height) {
        if (scannerPanel.getVisibility() == View.VISIBLE) {
            startScanner(surface, width, height);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(
            SurfaceTexture surface, int width, int height) {
        // The selected camera preview remains valid. Activity recreation handles
        // major configuration changes.
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        QrScanner scanner = qrScanner;
        if (scanner != null) {
            scanner.stop();
            qrScanner = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // No work. Camera preview frames are delivered through Camera callbacks.
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != CAMERA_PERMISSION_REQUEST) {
            return;
        }

        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openScanner();
        } else {
            Toast.makeText(
                    this,
                    R.string.camera_permission_denied,
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (scannerPanel.getVisibility() == View.VISIBLE) {
            closeScanner();
            return;
        }

        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        closeScanner();

        /*
         * Do not interrupt the command worker or terminate its child process.
         * shutdown() rejects future tasks but allows the current command,
         * stdout reader, and stderr drainer to finish normally.
         */
        commandExecutor.shutdown();
        super.onDestroy();
    }
}
