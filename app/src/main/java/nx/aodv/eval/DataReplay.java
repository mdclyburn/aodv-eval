package nx.aodv.eval;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import android.util.Log;

public class DataReplay {

    private ArrayList<Row> allData;
    private ArrayList<Data> pendingData;

    private static final int COL_TIMESTAMP = 0;
    private static final int COL_SOURCE = 1;
    private static final int COL_PAYLOAD = 3;

    public DataReplay() {
        this.allData = new ArrayList<Row>();
        this.pendingData = new ArrayList<Data>();
    }

    public void load(InputStream istream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(istream));
        String line;

        while((line = reader.readLine()) != null) {
            String[] cols = line.split("|");
            Row row = new Row();
            row.time = cols[this.COL_TIMESTAMP];
            row.source = cols[this.COL_SOURCE];
            row.payload = cols[this.COL_PAYLOAD];

            this.allData.add(row);
        }

        Log.v("perf", "Added " + this.allData.size() + " rows to data replay.");

        return;
    }

    public void sendAs(String source) {
        SimpleDateFormat timestampFormat = new SimpleDateFormat("hh:mm:ss.SSS");
        Date t0 = null;

        this.pendingData.clear();

        for(Row row : this.allData) {
            if (row.source != source) { continue; }

            Date time;
            try {
                time = timestampFormat.parse(row.time);
                if (t0 == null) {
                    t0 = time;
                }
            } catch (ParseException e) {
                Log.e("perf", "Failed to parse timestamp: " + row.time);
                return;
            }
            Calendar c = Calendar.getInstance();
            c.setTime(t0);
            long t0Millis = c.getTimeInMillis();
            c.setTime(time);
            long t1Millis = c.getTimeInMillis();
            long offset = t1Millis - t0Millis;

            // Parse out the payload bytes.
            String[] hsBytes = row.payload.split(":");
            byte[] payloadBytes = new byte[hsBytes.length];
            int index = 0;
            for(String hsByte : hsBytes) {
                try {
                    byte b = Byte.parseByte(hsByte, 16);
                    payloadBytes[index++] = b;
                } catch (NumberFormatException e) {
                    Log.e("perf", "Failed to parse payload data: " + row.payload);
                    return;
                }
            }

            pendingData.add(new Data(offset, payloadBytes));
        }
    }

    public HashSet<String> sources() {
        HashSet<String> s = new HashSet<String>();
        for (Row row : this.allData) {
            s.add(row.source);
        }

        return s;
    }

    public boolean hasNext() {
        return this.pendingData.size() > 0;
    }

    public long nextOffset() {
        return this.pendingData.get(0).offset;
    }

    public byte[] fetchPayload() {
        Data fetched = this.pendingData.remove(0);
        return fetched.payload;
    }

    private final class Row {
        public String time;
        public String source;
        public String dest;
        public String payload;
    }

    private final class Data {
        public long offset;
        public byte[] payload;

        public Data(long offset, byte[] payload) {
            this.offset = offset;
            this.payload = payload;
        }
    }
}
