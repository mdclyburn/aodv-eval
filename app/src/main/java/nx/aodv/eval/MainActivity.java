package nx.aodv.eval;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Bundle;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;

import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.location.nearby.apps.connectedcrossroad.R;

import java.io.IOException;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;

/**
 * Activity controlling the Message Board
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "connectedcrossroad";
    //private static final String LATENCY = "latency_tag";

    private static final String[] REQUIRED_PERMISSIONS =
            new String[]{
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.INTERNET,
            };

    private static final int REQUEST_CODE_REQUIRED_PERMISSIONS = 1;
    private static final short MAX_ADDRESS = 10;

    // Our handle to Nearby Connections
    private ConnectionsClient connectionsClient;

    //private Network network;
    private AODVNetwork network;

    private DataReplay dataReplay;

    private Button sendMessageButton;
    private Button setAddressButton;
    private Button sendBurstButton;
    private Button loadDataButton;
    private Button sendDataButton;
    private Button sendAsButton;

    private TextView tvNumConnected;
    private TextView tvName;
    private TextView tvLatestTx;
    private TextView tvLatestRx;
    private TextView tvRouteTable;
    private TextView tvBytesRx;
    private TextView tvLoadedData;

    private EditText editTextSendMessage;
    private EditText editTextSendAddress;
    private EditText editTextSetAddress;
    
    private TextView tvHelloInterval;
    private TextView tvRouteExpiryInterval;
    private TextView tvRouteTimeout;
    private TextView tvQueueTimeout;
    private TextView tvQueueInterval;
    private TextView tvQueuePollingTimeout;

    private String datasetPath;
    private String[] txDatasets;
    private AlertDialog datasetPicker;
    private AlertDialog sourcePicker;
    private String[] datasetSources;


    @Override
    protected void onCreate(@Nullable Bundle bundle) {

        super.onCreate(bundle);
        setContentView(R.layout.activity_main);

        dataReplay = new DataReplay();

        sendMessageButton = findViewById(R.id.sendMessageButton);
        setAddressButton = findViewById(R.id.setAddressButton);
        sendBurstButton = findViewById(R.id.sendBurstButton);
        loadDataButton = findViewById(R.id.buttonLoadData);
        sendDataButton = findViewById(R.id.sendDatasetButton);
        sendDataButton.setEnabled(false);
        sendAsButton = findViewById(R.id.sendAsButton);
        sendAsButton.setEnabled(false);

        tvName = findViewById(R.id.deviceName);
        tvNumConnected = findViewById(R.id.numConnectionsText);
        tvLatestTx = findViewById(R.id.lastMessageTx);
        tvLatestRx = findViewById(R.id.lastMessageRx);
        tvRouteTable = findViewById(R.id.textViewRouteTable);

        editTextSendMessage = findViewById(R.id.editTextField);
        editTextSendAddress = findViewById(R.id.editAddressField);
        editTextSetAddress = findViewById(R.id.setAddressField);

        tvHelloInterval = findViewById(R.id.editHelloInterval);
        tvRouteExpiryInterval = findViewById(R.id.editRouteExpiryInterval);
        tvRouteTimeout = findViewById(R.id.editRouteTimeout);
        tvQueueTimeout = findViewById(R.id.editQueueTimeout);
        tvQueueInterval = findViewById(R.id.editQueueInterval);
        tvQueuePollingTimeout = findViewById(R.id.editQueuePollingTimeout);

        sendMessageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
//                network.setIntervalsAndTimeouts(
//                        2000,
//                        3000,
//                        7000,
//                        7000,
//                        500,
//                        5000
////                        Integer.parseInt(tvHelloInterval.getText().toString()),
////                        Integer.parseInt(tvRouteExpiryInterval.getText().toString()),
////                        Integer.parseInt(tvRouteTimeout.getText().toString()),
////                        Integer.parseInt(tvQueueTimeout.getText().toString()),
////                        Integer.parseInt(tvQueueInterval.getText().toString()),
////                        Integer.parseInt(tvHelloInterval.getText().toString())
//                );

                String name = editTextSendAddress.getText().toString();
                short address = strToShort(name);
                if (address != 0) {
                    sendMessage(address, editTextSendMessage.getText().toString());
                }
            }
        });

        connectionsClient = Nearby.getConnectionsClient(this);
        network = new AODVNetwork(connectionsClient,
                tvNumConnected,
                tvLatestRx,
                tvRouteTable);

        tvName.setText(String.format("Device name: %s", network.getAddress()));

        setAddressButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String name = editTextSetAddress.getText().toString().trim();
                short address = strToShort(name);
                if (address != 0) {
                    network.setAddress(address);
                    tvName.setText(String.format("Device name: %s", address));
                    //disable the button and field so they can't be set again
                    editTextSetAddress.setEnabled(false);
                    setAddressButton.setEnabled(false);
                    //start network operations after address is set
                    network.start();
                }
            }
        });

        sendBurstButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String name = editTextSendAddress.getText().toString();
                short address = strToShort(name);
                if (address != 0) {
                    for (int x = 0; x < 5; x++) {
                        sendMessage(address, editTextSendMessage.getText().toString());

                        try {
                            TimeUnit.SECONDS.sleep(1);
                        } catch (InterruptedException interrupted) {
                            Log.e("perf", "Failed to sleep for burst send testing.");
                        }
                    }
                }
            }
        });
        //this.setAddressClicked();

        final android.content.Context theContext = this;
        this.tvLoadedData = findViewById(R.id.loadedDataTextView);
        this.tvLoadedData.setText("(No dataset)");
        try {
            final AssetManager assetManager = this.getAssets();
            txDatasets = assetManager.list("tx-data/");
            datasetPath = null;
            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
            alertDialogBuilder
                    .setTitle("Pick dataset")
                    .setItems(txDatasets, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int index) {
                            Log.v("perf", "User chose item " + index + ": " + txDatasets[index]);
                            datasetPath = "tx-data/" + txDatasets[index];
                            tvLoadedData.setText("Current dataset: " + txDatasets[index]);

                            if (txDatasets[index].endsWith(".csv")) {
                                Log.v("perf", "Loading " + datasetPath);
                                try {
                                    dataReplay.load(assetManager.open(datasetPath));
                                } catch (IOException e) {
                                    Log.e("perf", "Failed to open '" + datasetPath + "': " + e.getMessage());
                                }
                            }

                            // populate dataset sources list
                            HashSet<String> possibleSources = dataReplay.sources();
                            datasetSources = new String[possibleSources.size()];
                            int idx = 0;
                            for (String source : possibleSources) {
                                datasetSources[idx++] = source;
                            }

                            AlertDialog.Builder sourceDialogBuilder = new AlertDialog.Builder(theContext);
                            sourceDialogBuilder
                                    .setTitle("Pick source")
                                    .setItems(datasetSources, new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int idx) {
                                            String source = datasetSources[idx];
                                            Log.v("perf", "Sending data from source " + source);

                                            dataReplay.sendAs(source);
                                            sendDataButton.setEnabled(true);
                                        }
                                    });
                            sourcePicker = sourceDialogBuilder.create();

                            sendAsButton.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View view) {
                                    sourcePicker.show();
                                }
                            });
                            sendAsButton.setEnabled(true);
                        }
                    });
            this.datasetPicker = alertDialogBuilder.create();

            this.loadDataButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    datasetPicker.show();
                }
            });
        } catch (IOException e) {
            Log.e("perf", "Could not list assets.");
            findViewById(R.id.buttonLoadData)
                    .setEnabled(false);
        }

        this.sendDataButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.v("perf", "Beginning data transmission.");
                // do not allow further button presses that could interrupt the process
                loadDataButton.setEnabled(false);
                sendAsButton.setEnabled(false);
                sendDataButton.setEnabled(false);

                String name = editTextSendAddress.getText().toString();
                final short scheduledToAddress = strToShort(name);

                Runnable scheduledSend = new Runnable() {
                    @Override
                    public void run() {
                        long waited = 0;
                        while (dataReplay.hasNext()) {
                            final long t = dataReplay.nextOffset();
                            final long waitMs = t - waited;
                            waited += waitMs;

                            try {
                                Thread.sleep(waitMs);
                            } catch (InterruptedException e) {
                                Log.e("perf", "Scheduled send interrupted during sleep.");
                            }

                            // turn this back into a string for now.
                            String data = "";
                            for (byte b : dataReplay.fetchPayload()) {
                                data += b;
                            }
                            network.sendMessage(scheduledToAddress, data);
                            Log.v("perf", "Sent data of length " + data.length() + " to " + scheduledToAddress);
                        }

                        Log.v("perf", "Done sending stuff on schedule.");
                        loadDataButton.setEnabled(true);
                        sendAsButton.setEnabled(true);
                        sendDataButton.setEnabled(true);
                    }
                };

                scheduledSend.run();
            }
        });
    }

    @Override
    protected void onStart() {

        super.onStart();

        if (!hasPermissions(this, REQUIRED_PERMISSIONS)) {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_REQUIRED_PERMISSIONS);
        }

    }

    @Override
    protected void onStop() {
        network.stop();
        super.onStop();
    }

    /**
     * Returns true if the app was granted all the permissions. Otherwise, returns false.
     */
    private static boolean hasPermissions(Context context, String... permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(context, permission) !=
                    PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Handles user acceptance (or denial) of our permission request.
     */
    @CallSuper
    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults)
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != REQUEST_CODE_REQUIRED_PERMISSIONS) {
            return;
        }

        for (int grantResult : grantResults) {
            if (grantResult == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(this, R.string.error_missing_permissions, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }
        recreate();
    }

    private void sendMessage(short id, String msg) {
        network.sendMessage(id, msg);
        Log.d(TAG, "sendMessage: Sent message");
        tvLatestTx.setText(String.format("%s: %s", id, msg));
    }

    //Ensure that string address is convertible to short address
    private short strToShort(String str) {
        short num = 0;
        try {
            short tmp = Short.parseShort(str);
            if (tmp > 0 && tmp <= MAX_ADDRESS) {
                num = tmp;
            } else {
                Toast.makeText(MainActivity.this, "Address out of range", Toast.LENGTH_SHORT).show();
            }
        } catch (NumberFormatException nfe) {
            Toast.makeText(MainActivity.this, "Address not number", Toast.LENGTH_SHORT).show();
        }
        return num;
    }

    private void setAddressClicked() {
        short address = 3;
        if (address != 0) {
            network.setAddress(address);
            tvName.setText(String.format("Device name: %s", address));
            //disable the button and field so they can't be set again
            editTextSetAddress.setEnabled(false);
            setAddressButton.setEnabled(false);
            //start network operations after address is set
            network.start();
        }
    }
}
