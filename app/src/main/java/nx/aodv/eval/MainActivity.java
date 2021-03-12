package nx.aodv.eval;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;

import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.location.nearby.apps.connectedcrossroad.R;

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

    private Button sendMessageButton;
    private Button setAddressButton;

    private TextView tvNumConnected;
    private TextView tvName;
    private TextView tvLatestTx;
    private TextView tvLatestRx;

    private EditText editTextSendMessage;
    private EditText editTextSendAddress;
    private EditText editTextSetAddress;

    @Override
    protected void onCreate(@Nullable Bundle bundle) {

        super.onCreate(bundle);
        setContentView(R.layout.activity_main);

        sendMessageButton = findViewById(R.id.sendMessageButton);
        setAddressButton = findViewById(R.id.setAddressButton);

        tvName = findViewById(R.id.deviceName);
        tvNumConnected = findViewById(R.id.numConnectionsText);
        tvLatestTx = findViewById(R.id.lastMessageTx);
        tvLatestRx = findViewById(R.id.lastMessageRx);

        editTextSendMessage = findViewById(R.id.editTextField);
        editTextSendAddress = findViewById(R.id.editAddressField);
        editTextSetAddress = findViewById(R.id.setAddressField);

        sendMessageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String name = editTextSendAddress.getText().toString();
                short address = strToShort(name);
                if (address != 0) {
                    sendMessage(address, editTextSendMessage.getText().toString());
                }
            }
        });

        connectionsClient = Nearby.getConnectionsClient(this);
        network = new AODVNetwork(connectionsClient, tvNumConnected, tvLatestRx);

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

}
