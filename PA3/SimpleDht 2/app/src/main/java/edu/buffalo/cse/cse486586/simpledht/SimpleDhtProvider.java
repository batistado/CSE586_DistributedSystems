package edu.buffalo.cse.cse486586.simpledht;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Formatter;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.telephony.TelephonyManager;
import android.util.Log;

public class SimpleDhtProvider extends ContentProvider {
    static final String TAG = SimpleDhtProvider.class.getSimpleName();
    static final String REMOTE_PORT0 = "11108";
    static final String REMOTE_PORT1 = "11112";
    static final String REMOTE_PORT2 = "11116";
    static final String REMOTE_PORT3 = "11120";
    static final String REMOTE_PORT4 = "11124";
    static final int SERVER_PORT = 10000;
    private static List<String> remotePorts = null;
    private String myPort = null;
    private String myPortHash = null;
    private String predecessor = null;
    private String successor = null;
    private String joinHandlerNode = "5554";
    private Set<String> fileNames;
    private boolean hasReceived = false;
    private Cursor cursor = new MatrixCursor(new String[]{"key", "value"});

    private class SenderTask extends AsyncTask<String, String, Void> {
        @Override
        protected Void doInBackground(String... params) {
            String operation = params[0];
            String remotePort = Integer.toString(Integer.parseInt(params[1]) * 2);
            String message = params[2];

            String fullMessage = operation + "|" + message + "EOM\n";

            Log.i("TEST", "Sending " + fullMessage + " to " + params[1]);
            try {
                Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                        Integer.parseInt(remotePort));

                String ack;

                InputStreamReader inputStreamReader = new InputStreamReader(socket.getInputStream());
                BufferedReader reader = new BufferedReader(inputStreamReader);
                PrintWriter printWriter = new PrintWriter(socket.getOutputStream());

                int count = 0;
                do {
                    printWriter.append(fullMessage);
                    printWriter.flush();
                    ack = reader.readLine();
                    count++;
                } while (count <= 10 && (ack == null || !ack.equals("ACK")));

                printWriter.close();
                reader.close();
                inputStreamReader.close();
                socket.close();
                socket.close();
            } catch (SocketException se) {
                se.printStackTrace();
                System.out.println("Socket Exception!");
            } catch (UnknownHostException uh) {
                uh.printStackTrace();
                System.out.println("Unknown Host Exception!");
            } catch (IOException ex) {
                ex.printStackTrace();
                System.out.println("IO Exception!");
            } catch (Exception e) {
                e.printStackTrace();
            }

            return null;
        }
    }

    private class ReceiverTask extends AsyncTask<ServerSocket, String, Void> {

        @Override
        protected Void doInBackground(ServerSocket... sockets) {
            ServerSocket serverSocket = sockets[0];
            while (true) {
                try {
                    Socket server = serverSocket.accept();
                    InputStreamReader inputStreamReader = new InputStreamReader(server.getInputStream());
                    BufferedReader reader = new BufferedReader(inputStreamReader);

                    String message = reader.readLine();
                    while (message == null || !message.endsWith("EOM")) {
                        message = reader.readLine();
                    }

                    PrintWriter printWriter = new PrintWriter(server.getOutputStream());

                    printWriter.append("ACK\n");
                    printWriter.flush();

                    inputStreamReader.close();
                    reader.close();
                    printWriter.close();
                    server.close();

                    Log.i("TEST", "Received message " + message);
                    publishProgress(message.substring(0, message.length() - 3));

                } catch (IOException e) {
                    e.printStackTrace();
                    break;
                }
            }

            return null;
        }

        @Override
        protected void onProgressUpdate(String... strings) {
            /*
             * The following code displays what is received in doInBackground().
             */
            String[] messages = strings[0].split("\\|");

            String operation = messages[0];

            if (operation.equals("JOIN")) {
                nodeJoinOperation(messages[1]);
            } else if (operation.equals("SET")) {
                nodeSetOperation(messages[1], messages[2]);
            } else if (operation.equals("INSERT")) {
                insertOperation(messages[1], messages[2]);
            } else if (operation.equals("SEARCH")) {
                searchContent(messages[1], messages[2]);
            } else if (operation.equals("FOUND")) {
                foundOne(messages[1], messages[2]);
            } else if (operation.equals("DELETE")) {
                deleteOne(messages[1], messages[2]);
            } else if (operation.equals("DELETEALL")) {
                deleteAll(messages[1], messages[2]);
            }
            else {
                // GETALL case
                getAll(messages[1], messages[2]);
            }

        }
    }

    private void deleteOne(String remotePort, String fileName) {
        if (remotePort.equals(myPort)){
            hasReceived = true;
            return;
        }

        String message = remotePort + "|" + fileName;
        if (fileNames.contains(fileName)) {
            getContext().deleteFile(fileName);
            fileNames.remove(fileName);
            new SenderTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, "DELETE", remotePort, message);
        } else {
            new SenderTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, "DELETE", successor, message);
        }
    }

    private void deleteAll(String remotePort, String fileName) {
        if (remotePort.equals(myPort)){
            hasReceived = true;
            return;
        }

        String message = remotePort + "|" + fileName;
        for (String localFile: fileNames) {
            getContext().deleteFile(localFile);
        }

        fileNames.clear();

        new SenderTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, "DELETEALL", successor, message);
    }

    private void getAll(String remotePort, String message) {
        try {
            String localContentValues = "";
            if (!remotePort.equals(myPort)) {
                for (String fileName : fileNames) {
                    InputStreamReader inputStreamReader = new InputStreamReader(getContext().openFileInput(fileName));
                    BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
                    localContentValues += fileName + ":" + bufferedReader.readLine() + "+";
                    bufferedReader.close();
                }

                // Remove last "+"
                if (localContentValues.length() > 0) {
                    localContentValues = localContentValues.substring(0, localContentValues.length() - 1);
                    message += "+" + localContentValues;
                }

                message = remotePort + "|" + message;

                new SenderTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, "GETALL", successor, message);
            } else {
                String[] contentValuePairs = message.split("\\+");
                for (int i = 1; i < contentValuePairs.length; i++) {
                    String[] splitCV = contentValuePairs[i].split(":");

                    ((MatrixCursor) cursor).newRow().add("key", splitCV[0])
                            .add("value", splitCV[1]);
                }

                hasReceived = true;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void foundOne(String fileName, String value) {
        ((MatrixCursor) cursor).newRow().add("key", fileName)
                .add("value", value);

        hasReceived = true;
    }

    private void searchContent(String remotePort, String fileName) {
        try {
            String message;
            if (fileNames.contains(fileName)) {
                InputStreamReader inputStreamReader = new InputStreamReader(getContext().openFileInput(fileName));
                BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
                message = fileName + "|" + bufferedReader.readLine();
                new SenderTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, "FOUND", remotePort, message);
                bufferedReader.close();
            } else {
                message = remotePort + "|" + fileName;
                new SenderTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, "SEARCH", successor, message);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void nodeSetOperation(String newPredecessor, String newSuccessor) {
        Log.i("TEST", "Received SET " + newPredecessor + " == " + newSuccessor);
        if (!newPredecessor.equals("None")) {
            predecessor = newPredecessor;
        }

        if (!newSuccessor.equals("None")) {
            successor = newSuccessor;
        }
    }

    private void nodeJoinOperation(String newNodePort) {
        Log.i("TEST", "Received JOIN on " + newNodePort);
        String newNodePortHash = genHash(newNodePort);

        String predecessorHash = null;
        if (predecessor != null) {
            predecessorHash = genHash(predecessor);
        }

        // message format: Predecessor | successor (no spaces)
        String message = null;

        // Base case when there are no successors or predecessors to the node
        if (predecessor == null && successor == null) {
            predecessor = newNodePort;
            successor = newNodePort;
            message = myPort + "|" + myPort;
            new SenderTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, "SET", newNodePort, message);
        } else if (predecessorHash.compareTo(myPortHash) < 0) {
            // This is normal ring

            if (predecessorHash.compareTo(newNodePortHash) < 0 && myPortHash.compareTo(newNodePortHash) >= 0) {
                updateNodes(newNodePort);
            } else {
                // Just forward the request to my successor
                new SenderTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, "JOIN", successor, newNodePort);
            }
        } else {
            // Ring ends here
            if (myPortHash.compareTo(newNodePortHash) < 0 && predecessorHash.compareTo(newNodePortHash) >= 0) {
                // Just forward the request to my successor
                new SenderTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, "JOIN", successor, newNodePort);
            } else {
                updateNodes(newNodePort);
            }
        }
    }

    private void updateNodes(String newNodePort) {
        String message;
        String oldPredecessor = predecessor;
        predecessor = newNodePort;


        // Update my old predecessor
        message = "None" + "|" + newNodePort;

        new SenderTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, "SET", oldPredecessor, message);

        // Respond to new node
        message = oldPredecessor + "|" + myPort;

        new SenderTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, "SET", newNodePort, message);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        // TODO Auto-generated method stub
        Log.i("TEST", "DELETE FOR " + selection);

        if (selection.equals("@")) {
            Log.i("TEST", "NOW DELETING FOR @");
            for (String fileName : fileNames) {
                getContext().deleteFile(fileName);
            }

            fileNames.clear();
        } else if (selection.equals("*")) {
            Log.i("TEST", "NOW DELETING FOR *");

            // Delete my local data first
            for (String fileName : fileNames) {
                getContext().deleteFile(fileName);
            }

            fileNames.clear();

            // Delete only if there are other nodes active
            if (successor != null) {

                String message = myPort + "|" + "*";
                new SenderTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, "DELETEALL", successor, message);

                while (!hasReceived) {
                    // Busy wait for response
                }

                hasReceived = false;
            }

        } else {
            Log.i("TEST", "NOW DELETING FOR FILE NAME");
            if (fileNames.contains(selection)) {
                getContext().deleteFile(selection);
                fileNames.remove(selection);
            } else {
                // search for selection
                String message = myPort + "|" + selection;
                new SenderTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, "DELETE", successor, message);

                while (!hasReceived) {
                    // Busy wait for response
                }

                hasReceived = false;
            }
        }
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        // TODO Auto-generated method stub
        return null;
    }

    public void writeToFileStorage(String fileName, String content) {
        Log.i("TEST", "Writing in File: " + fileName + " data: " + content);
        try {
            FileOutputStream outputStream = getContext().openFileOutput(fileName, Context.MODE_PRIVATE);
            outputStream.write(content.getBytes());
            outputStream.close();
            fileNames.add(fileName);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        // TODO Auto-generated method stub
        Map.Entry entry;
        String content = "";
        String fileName;

        Set<Map.Entry<String, Object>> set = values.valueSet();
        Iterator itr = set.iterator();
        boolean isValue = true;

        while (itr.hasNext()) {
            entry = (Map.Entry) itr.next();

            if (isValue) {
                content = entry.getValue().toString();
            } else {
                fileName = entry.getValue().toString();
                insertOperation(fileName, content);
            }

            isValue = !isValue;
        }
        return uri;
    }

    private void insertOperation(String fileName, String content) {
        Log.i("TEST", "Received INSERT for " + fileName + " with data: " + content);

        String predecessorHash = null;
        if (predecessor != null) {
            predecessorHash = genHash(predecessor);
        }

        String fileNameHash = genHash(fileName);

        // message format: fileName | content (no spaces)
        String message = fileName + "|" + content;

        // Base case when there are no successors or predecessors to the node
        if (predecessor == null && successor == null) {
            writeToFileStorage(fileName, content);
        } else if (predecessorHash.compareTo(myPortHash) <= 0) {
            // This is normal ring

            if (predecessorHash.compareTo(fileNameHash) < 0 && myPortHash.compareTo(fileNameHash) >= 0) {
                writeToFileStorage(fileName, content);
            } else {
                // Just forward the request to my successor
                new SenderTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, "INSERT", successor, message);
            }
        } else {
            // Ring ends here
            if (myPortHash.compareTo(fileNameHash) < 0 && predecessorHash.compareTo(fileNameHash) >= 0) {
                // Just forward the request to my successor
                new SenderTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, "INSERT", successor, message);
            } else {
                writeToFileStorage(fileName, content);
            }
        }
    }

    @Override
    public boolean onCreate() {
        // TODO Auto-generated method stub
        // TODO: Remove this later, not needed


        fileNames = new HashSet<String>();
        Log.i("TEST", "CP On create");
        String ports[] = {REMOTE_PORT0, REMOTE_PORT1, REMOTE_PORT2, REMOTE_PORT3, REMOTE_PORT4};
        remotePorts = new ArrayList<String>();

        remotePorts.addAll(Arrays.asList(ports));

        TelephonyManager tel = (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
        String portStr = tel.getLine1Number().substring(tel.getLine1Number().length() - 4);
        myPort = String.valueOf(Integer.parseInt(portStr));
        myPortHash = genHash(myPort);

        try {
            ServerSocket serverSocket = new ServerSocket(SERVER_PORT);
            new ReceiverTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, serverSocket);
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "Can't create a ServerSocket");
            return false;
        }

        Log.i("TEST", "MY PORT: " + myPort);

        if (!myPort.equals(joinHandlerNode)) {
            new SenderTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, "JOIN", joinHandlerNode, myPort);
        }

        return false;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        // TODO Auto-generated method stub
        cursor = new MatrixCursor(new String[]{"key", "value"});

        try {
            Log.i("TEST", "QUERY FOR " + selection);

            if (selection.equals("@")) {
                Log.i("TEST", "NOW QUERYING FOR @");
                for (String fileName : fileNames) {
                    InputStreamReader inputStreamReader = new InputStreamReader(getContext().openFileInput(fileName));
                    BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

                    ((MatrixCursor) cursor).newRow().add("key", fileName)
                            .add("value", bufferedReader.readLine());
                    bufferedReader.close();
                }

                return cursor;
            } else if (selection.equals("*")) {
                Log.i("TEST", "NOW QUERYING FOR *");
                // Reset global cursor
                cursor = new MatrixCursor(new String[]{"key", "value"});

                // Add my local data first
                for (String fileName : fileNames) {
                    InputStreamReader inputStreamReader = new InputStreamReader(getContext().openFileInput(fileName));
                    BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
                    ((MatrixCursor) cursor).newRow().add("key", fileName)
                            .add("value", bufferedReader.readLine());
                    bufferedReader.close();
                }

                // GETALL only if there are other nodes active
                if (successor != null) {

                    String message = myPort + "|" + "*";
                    new SenderTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, "GETALL", successor, message);

                    while (!hasReceived) {
                        // Busy wait for response
                    }

                    hasReceived = false;
                    Log.i("TEST", "* QUERY returned " + Integer.toString(cursor.getCount()) + " rows");
                }

                return cursor;

            } else {
                Log.i("TEST", "NOW QUERYING FOR FILE NAME");
                if (fileNames.contains(selection)) {
                    InputStreamReader inputStreamReader = new InputStreamReader(getContext().openFileInput(selection));
                    BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

                    ((MatrixCursor) cursor).newRow().add("key", selection)
                            .add("value", bufferedReader.readLine());
                    bufferedReader.close();
                } else {
                    // Reset global cursor
                    cursor = new MatrixCursor(new String[]{"key", "value"});

                    // search for selection
                    String message = myPort + "|" + selection;
                    new SenderTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, "SEARCH", successor, message);

                    while (!hasReceived) {
                        // Busy wait for response
                    }

                    hasReceived = false;
                    return cursor;
                }
            }


            InputStreamReader inputStreamReader = new InputStreamReader(getContext().openFileInput(selection));
            BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

            ((MatrixCursor) cursor).newRow().add("key", selection)
                    .add("value", bufferedReader.readLine());
        } catch (Exception e) {
            cursor.close();
            return null;
        }

        return cursor;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        // TODO Auto-generated method stub
        return 0;
    }

    private String genHash(String input) {
        MessageDigest sha1 = null;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
            byte[] sha1Hash = sha1.digest(input.getBytes());
            Formatter formatter = new Formatter();
            for (byte b : sha1Hash) {
                formatter.format("%02x", b);
            }
            return formatter.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return null;
    }
}
