package edu.buffalo.cse.cse486586.groupmessenger2;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.StrictMode;
import android.telephony.TelephonyManager;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * GroupMessengerActivity is the main Activity for the assignment.
 * 
 * @author stevko
 *
 */
public class GroupMessengerActivity extends Activity {
    static final String TAG = GroupMessengerActivity.class.getSimpleName();
    static final String REMOTE_PORT0 = "11108";
    static final String REMOTE_PORT1 = "11112";
    static final String REMOTE_PORT2 = "11116";
    static final String REMOTE_PORT3 = "11120";
    static final String REMOTE_PORT4 = "11124";
    static final int SERVER_PORT = 10000;
    private static final String KEY_FIELD = "key";
    private static final String VALUE_FIELD = "value";
    private ArrayList<String> remotePorts = null;
    private ContentResolver mContentResolver;
    private Uri mUri = null;
    private ReentrantLock lock = new ReentrantLock();
    static int serial = -1;
    private TextView tv;
    private Queue<Integer> queue;
    private Map<Integer, String> messageQueue;

    private Uri buildUri(String scheme, String authority) {
        Uri.Builder uriBuilder = new Uri.Builder();
        uriBuilder.authority(authority);
        uriBuilder.scheme(scheme);
        return uriBuilder.build();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_messenger);

        queue = new PriorityQueue<Integer>();
        messageQueue = new HashMap<Integer, String>();

        tv = (TextView) findViewById(R.id.textView1);
        tv.setMovementMethod(new ScrollingMovementMethod());

        mUri = buildUri("content", "edu.buffalo.cse.cse486586.groupmessenger2.provider");
        mContentResolver = getContentResolver();

        /*
         * Registers OnPTestClickListener for "button1" in the layout, which is the "PTest" button.
         * OnPTestClickListener demonstrates how to access a ContentProvider.
         */
        findViewById(R.id.button1).setOnClickListener(
                new OnPTestClickListener(tv, getContentResolver()));

        TelephonyManager tel = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
        String portStr = tel.getLine1Number().substring(tel.getLine1Number().length() - 4);
        final String myPort = String.valueOf((Integer.parseInt(portStr) * 2));

        String ports[] = {REMOTE_PORT0, REMOTE_PORT1, REMOTE_PORT2, REMOTE_PORT3, REMOTE_PORT4};
        remotePorts = new ArrayList<String>();

        remotePorts.addAll(Arrays.asList(ports));

        try {
            ServerSocket serverSocket = new ServerSocket(SERVER_PORT);
            new ReceiverTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, serverSocket);
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "Can't create a ServerSocket");
            return;
        }


        final EditText editText1 = (EditText) findViewById(R.id.editText1);
        final Button sendButton = (Button) findViewById(R.id.button4);


        sendButton.setOnClickListener(new Button.OnClickListener() {
            public void onClick(View v) {
                String msg = editText1.getText().toString();
                editText1.setText("");

                lock.lock();
                serial++;
                restoreMessages(msg, serial);
                lock.unlock();

                new SenderTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, msg, Integer.toString(serial));
            }
        });
    }

    private class SenderTask extends AsyncTask<String, String, Void> {

        @Override
        protected Void doInBackground(String... msgs) {
            String message = msgs[0];
            message = message + "END" + msgs[1] + "EOM\n";
            try {
                for (String remotePort : remotePorts) {
                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                            Integer.parseInt(remotePort));

                    String ack;

                    InputStreamReader inputStreamReader = new InputStreamReader(socket.getInputStream());
                    BufferedReader reader = new BufferedReader(inputStreamReader);
                    PrintWriter printWriter = new PrintWriter(socket.getOutputStream());

                    do {
                        printWriter.append(message);
                        printWriter.flush();
                        ack = reader.readLine();
                    } while (ack == null || !ack.equals("ACK"));

                    socket.close();
                }
            } catch (SocketException se) {
                se.printStackTrace();
                System.out.println("Socket Exception!");
            } catch (UnknownHostException uh) {
                uh.printStackTrace();
                System.out.println("Unknown Host Exception!");
            } catch (IOException ex) {
                ex.printStackTrace();
                System.out.println("IO Exception!");
            }

            publishProgress(msgs);
            return null;
        }

        @Override
        protected void onProgressUpdate(String...strings) {
            /*
             * The following code displays what is received in doInBackground().
             */

        }
    }

    private class ReceiverTask extends AsyncTask<ServerSocket, String, Void> {

        @Override
        protected Void doInBackground(ServerSocket... sockets) {
            ServerSocket serverSocket = sockets[0];
            while (true){
                try {
                    Socket server = serverSocket.accept();
                    InputStreamReader inputStreamReader = new InputStreamReader(server.getInputStream());
                    BufferedReader reader = new BufferedReader(inputStreamReader);

                    String message = reader.readLine();
                    while (message == null || !message.endsWith("EOM")) {
                        message = reader.readLine();
                    }

                    publishProgress(message.substring(0, message.length() - 3));
                    PrintWriter printWriter = new PrintWriter(server.getOutputStream());

                    printWriter.append("ACK\n");
                    printWriter.flush();

                    server.close();

                } catch (IOException e) {
                    e.printStackTrace();
                    break;
                }
            }

            return null;
        }

        @Override
        protected void onProgressUpdate(String...strings) {
            /*
             * The following code displays what is received in doInBackground().
             */
            String strReceived = strings[0].trim();
            int delimiterIndex = strReceived.lastIndexOf("END");
            String value = strReceived.substring(0, delimiterIndex);
            int serial = Integer.parseInt(strReceived.substring(delimiterIndex + 3));

            saveMessage(value, serial);
        }
    }

    public void restoreMessages(String value, int serial){
        storeMessage(value, serial);
        GroupMessengerActivity.serial = serial;

        while (!queue.isEmpty() && queue.peek() == serial + 1){
            int savedSerial = queue.poll();
            storeMessage(messageQueue.get(savedSerial), savedSerial);
            serial = savedSerial;
            GroupMessengerActivity.serial = serial;
        }
    }

    public void storeMessage(String value, int serial){
        ContentValues cv = new ContentValues();
        cv.put(KEY_FIELD, serial);
        cv.put(VALUE_FIELD, value);
        mContentResolver.insert(mUri, cv);
        tv.append(value + "\n");
    }

    public void saveMessage(String value, int serial) {
        lock.lock();
        if (serial == GroupMessengerActivity.serial + 1){
            restoreMessages(value, serial);
            lock.unlock();
            new SenderTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, value, Integer.toString(serial));
        } else if (serial > GroupMessengerActivity.serial + 1){
            queue.add(serial);
            messageQueue.put(serial, value);
        }

        if (lock.isHeldByCurrentThread())
            lock.unlock();
    }

    /***
     * ClientTask is an AsyncTask that should send a string over the network.
     * It is created by ClientTask.executeOnExecutor() call whenever OnKeyListener.onKey() detects
     * an enter key press event.
     *
     * @author stevko
     *
     */

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.activity_group_messenger, menu);
        return true;
    }
}
