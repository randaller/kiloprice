package com.pizdadeal.kiloprice;

import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import android.util.Log;
import android.util.SparseArray;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;

import com.google.android.gms.vision.CameraSource;
import com.google.android.gms.vision.Detector;
import com.google.android.gms.vision.text.Text;
import com.google.android.gms.vision.text.TextBlock;
import com.google.android.gms.vision.text.TextRecognizer;

import java.io.IOException;

import java.util.List;
import java.text.DecimalFormat;

public class MainActivity extends AppCompatActivity {
    SurfaceView mCameraView;
    TextView mTextView;
    CameraSource mCameraSource;

    private static final String TAG = "MainActivity";
    private static final int requestPermissionID = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mCameraView = findViewById(R.id.surfaceView);
        mTextView = findViewById(R.id.text_view);

        startCameraSource();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode != requestPermissionID) {
            Log.d(TAG, "Got unexpected permission result: " + requestCode);
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }

        if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            try {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                mCameraSource.start(mCameraView.getHolder());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void startCameraSource() {
        // Create the TextRecognizer
        final TextRecognizer textRecognizer = new TextRecognizer.Builder(getApplicationContext()).build();

        if (!textRecognizer.isOperational()) {
            Log.w(TAG, "Detector dependencies not loaded yet");
        } else {

            // initialize camera source to use high resolution and set Autofocus on
            mCameraSource = new CameraSource.Builder(getApplicationContext(), textRecognizer).setFacing(CameraSource.CAMERA_FACING_BACK).setRequestedPreviewSize(1280, 1024).setAutoFocusEnabled(true).setRequestedFps(2.0f).build();

            /**
             * Add call back to SurfaceView and check if camera permission is granted.
             * If permission is granted we can start our cameraSource and pass it to surfaceView
             */
            mCameraView.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    try {
                        if (ActivityCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {

                            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.CAMERA}, requestPermissionID);
                            return;
                        }
                        mCameraSource.start(mCameraView.getHolder());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    mCameraSource.stop();
                }
            });

            // Set the TextRecognizer's Processor.
            textRecognizer.setProcessor(new Detector.Processor<TextBlock>() {
                @Override
                public void release() {
                }

                @Override
                public void receiveDetections(Detector.Detections<TextBlock> detections) {
                    final SparseArray<TextBlock> items = detections.getDetectedItems();
                    if (items.size() != 0) {
                        mTextView.post(new Runnable() {
                            @Override
                            public void run() {
                                StringBuilder stringBuilder = new StringBuilder();
                                double highestElementHeight = 0.0;
                                String highestElementText = null;

                                // concatenate recognized text and search for highest :) price
                                for (int i = 0; i < items.size(); i++) {
                                    TextBlock item = items.valueAt(i);
                                    for (Text line : item.getComponents()) {
                                        for (Text element : line.getComponents()) {
                                            stringBuilder.append(element.getValue());
                                            stringBuilder.append(" ");

                                            // calculate the height of the current Text element
                                            double elementHeight = element.getBoundingBox().height();
                                            if (elementHeight > highestElementHeight && element.getValue().matches("[\\d.]+")) {
                                                highestElementHeight = elementHeight;
                                                highestElementText = element.getValue();
                                            }
                                        }
                                    }
                                }

                                // parse weight/volume
                                String[] parts = stringBuilder.toString().split("\\s+");
                                String weightMatch = null;
                                String priceMatch = null;
                                boolean foundFirstMatch = false;

                                for (String part : parts) {
                                    if (!foundFirstMatch && part.matches("\\d+([.,]\\d+)?[rpn]")) {
                                        // patterns like "100r", "100rp", "0.9n" (5ka)
                                        weightMatch = part;
                                        foundFirstMatch = true;
                                    } else if (foundFirstMatch && part.matches("\\d+")) {
                                        // If a weightMatch has been found, this matches patterns like "100"
                                        priceMatch = part;
                                        break;
                                    } else if (!foundFirstMatch && part.matches("\\d+([.,]\\d+)?[rpf]P")) {
                                        // patterns like "800rP", "800fP" (Lenta)
                                        weightMatch = part;
                                        foundFirstMatch = true;
                                    } else if (!foundFirstMatch && part.matches("\\d+([.,]\\d+)?[LA]")) {
                                        // patterns like "0.9L" (Lenta), "0.5A" (Perekrestok)
                                        weightMatch = part;
                                        foundFirstMatch = true;
                                    } else if (!foundFirstMatch && part.matches("\\d+MA")) {
                                        // patterns like "950MA" (Perekrestok) and converts digits to milliliters
                                        String digitsPart = part.replaceAll("[^\\d.]", "");
                                        double mlValue = Double.parseDouble(digitsPart) / 1000.0;
                                        DecimalFormat df = new DecimalFormat("#.###");
                                        weightMatch = df.format(mlValue) + "L";
                                        foundFirstMatch = true;
                                    }
                                }

                                // use highest text block instead of dummy post-regular
                                if (highestElementText != null) {
                                    priceMatch = highestElementText;
                                }

                                if (weightMatch != null && priceMatch != null) {
                                    // cleanup weight/volume
                                    String weightDigits = weightMatch.replaceAll("[^0-9,.]", "");
                                    weightDigits = weightDigits.replaceAll(",", ".");

                                    // cleanup price
                                    String priceDigits = priceMatch.replaceAll("[^0-9,.]", "");
                                    priceDigits = priceDigits.replaceAll(",", ".");

                                    stringBuilder.setLength(0);
                                    stringBuilder.append(weightMatch);
                                    stringBuilder.append(" @ ");
                                    stringBuilder.append(priceMatch);

                                    double realPrice = 0.0;
                                    if (weightMatch.endsWith("n") || weightMatch.endsWith("L") || weightMatch.endsWith("A")) {
                                        // liters
                                        realPrice = Double.parseDouble(priceDigits) / Double.parseDouble(weightDigits);
                                    } else {
                                        // kilograms
                                        realPrice = Double.parseDouble(priceDigits) * 1000 / Double.parseDouble(weightDigits);
                                    }

                                    stringBuilder.append(" = ");
                                    DecimalFormat decimalFormat = new DecimalFormat("#.##");
                                    stringBuilder.append(decimalFormat.format(realPrice));
                                } else {
                                    // stringBuilder.setLength(0);
                                }

                                mTextView.setText(stringBuilder.toString());
                            }
                        });
                    }
                }

            });
        }
    }
}
