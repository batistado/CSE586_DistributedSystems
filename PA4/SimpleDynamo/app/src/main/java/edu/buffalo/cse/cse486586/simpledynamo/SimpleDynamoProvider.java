package edu.buffalo.cse.cse486586.simpledynamo;

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
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.telephony.TelephonyManager;
import android.util.Log;

public class SimpleDynamoProvider extends ContentProvider {
    static final String TAG = SimpleDynamoProvider.class.getSimpleName();
    static final String REMOTE_PORT0 = "5554";
    static final String REMOTE_PORT1 = "5556";
    static final String REMOTE_PORT2 = "5558";
    static final String REMOTE_PORT3 = "5560";
    static final String REMOTE_PORT4 = "5562";
    static final int SERVER_PORT = 10000;
    private static List<String> remotePorts = null;
    private String myPort = null;
    private String myPortHash = null;
    private LinkedList<String> nodeOrder;
    private Set<String> fileNames;
    private boolean replicationDone = false;

    private class RecoveryTask extends AsyncTask<String, Void, Void> {
        @Override
        protected Void doInBackground(String... strings) {
            fileNames.clear();
            Log.i("TEST", "After recovery, files in storage: " + getContext().fileList().length);
//            for (String file : getContext().fileList()) {
//                getContext().deleteFile(file);
//            }
            handleNodeReJoin();

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

                    Log.i("TEST", "Received message " + message);

                    String response = checkOperation(message.substring(0, message.length() - 3)) + "EOM\n";

                    Log.i("TEST", "Sending response message " + response);
                    String ack;
                    int count = 0;
                    do {
                        printWriter.append(response);
                        printWriter.flush();
                        ack = reader.readLine();
                        count++;
                    } while (count <= 10 && (ack == null || !ack.equals("ACK")));

                    printWriter.flush();

                    Log.i("TEST", "Sent response message " + response);

                    inputStreamReader.close();
                    reader.close();
                    printWriter.close();
                    server.close();
                } catch (IOException e) {
                    e.printStackTrace();
                    break;
                }
            }

            return null;
        }

        private String checkOperation(String operationMsg) {
            String[] messages = operationMsg.split("\\|");

            String operation = messages[0];

            if (operation.equals("INSERT")) {
                return writeToFileStorage(messages[1], messages[2]);
            } else if (operation.equals("SEARCH")) {
                return queryMessage(messages[1]);
            } else if (operation.equals("DELETE")) {
                return deleteFiles(messages[1]);
            } else if (operation.equals("GETSELF")) {
                return getSelfMessage(messages[1]);
            }

            return "";
        }

        private String getSelfMessage(String remotePort) {
            try {
                int remoteIndex = nodeOrder.lastIndexOf(remotePort);
                String predecessor = remoteIndex == 0 ? nodeOrder.get(nodeOrder.size() - 1) : nodeOrder.get(remoteIndex - 1);

                String currNodeHash = genHash(remotePort);
                String preNodeHash = genHash(predecessor);

                String localContentValues = "";
                for (String fileName : fileNames) {
                    String fileNameHash = genHash(fileName);
                    if (preNodeHash.compareTo(currNodeHash) < 0) {
                        // Normal ring

                        if (preNodeHash.compareTo(fileNameHash) < 0 && currNodeHash.compareTo(fileNameHash) >= 0) {
                            InputStreamReader inputStreamReader = new InputStreamReader(getContext().openFileInput(fileName));
                            BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
                            localContentValues += fileName + ":" + bufferedReader.readLine() + "+";
                            bufferedReader.close();
                        }
                    } else {
                        // End ring case

                        if (!(currNodeHash.compareTo(fileNameHash) < 0 && preNodeHash.compareTo(fileNameHash) >= 0)) {
                            InputStreamReader inputStreamReader = new InputStreamReader(getContext().openFileInput(fileName));
                            BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
                            localContentValues += fileName + ":" + bufferedReader.readLine() + "+";
                            bufferedReader.close();
                        }
                    }
                }

                // Remove last "+"
                if (localContentValues.length() > 0) {
                    localContentValues = localContentValues.substring(0, localContentValues.length() - 1);
                }

                Log.i("TEST", "SELF MESSAGE: " + localContentValues);
                return localContentValues;
            } catch (IOException e) {
                e.printStackTrace();
            }

            return "";
        }

        private String queryMessage(String query) {
            try {
                if (query.equals("*")) {
                    String localContentValues = "";
                    for (String fileName : fileNames) {
                        InputStreamReader inputStreamReader = new InputStreamReader(getContext().openFileInput(fileName));
                        BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
                        localContentValues += fileName + ":" + bufferedReader.readLine() + "+";
                        bufferedReader.close();
                    }

                    // Remove last "+"
                    if (localContentValues.length() > 0) {
                        localContentValues = localContentValues.substring(0, localContentValues.length() - 1);
                    }

                    return localContentValues;
                } else {
                    InputStreamReader inputStreamReader = new InputStreamReader(getContext().openFileInput(query));
                    BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
                    String message = query + ":" + bufferedReader.readLine();
                    bufferedReader.close();

                    return message;
                }
            } catch (IOException e) {
                Log.i("TEST", "Error for query " + query);
                e.printStackTrace();
                return "";
            }
        }
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        // TODO Auto-generated method stub


        while (!replicationDone) {
            // Busy wait
        }

        Log.i("TEST", "DELETE FOR " + selection);
        String selectionHash = genHash(selection);

        if (selection.equals("@")) {
            Log.i("TEST", "NOW DELETING FOR @");
            for (String fileName : fileNames) {
                getContext().deleteFile(fileName);
            }

            fileNames.clear();
        } else if (selection.equals("*")) {
            Log.i("TEST", "NOW DELETING FOR *");

            for (String node : nodeOrder) {
                sendDeleteMessage(node, selection);
            }

        } else {
            Log.i("TEST", "NOW DELETING FOR FILE NAME");
            if (fileNames.contains(selection)) {
                getContext().deleteFile(selection);
                fileNames.remove(selection);
            } else {
                int i = 0;

                String currNodeHash, preNodeHash;
                while (i < nodeOrder.size()) {
                    if (i == 0) {
                        currNodeHash = genHash(nodeOrder.get(i));
                        preNodeHash = genHash(nodeOrder.get(nodeOrder.size() - 1));
                    } else {
                        currNodeHash = genHash(nodeOrder.get(i));
                        preNodeHash = genHash(nodeOrder.get(i - 1));
                    }


                    if (preNodeHash.compareTo(currNodeHash) < 0) {
                        // Normal ring

                        if (preNodeHash.compareTo(selectionHash) < 0 && currNodeHash.compareTo(selectionHash) >= 0) {
                            break;
                        }
                    } else {
                        // End ring case

                        if (!(currNodeHash.compareTo(selectionHash) < 0 && preNodeHash.compareTo(selectionHash) >= 0)) {
                            break;
                        }
                    }

                    i++;
                }

                handleDelete(i, 3, selection);
            }
        }
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        // TODO Auto-generated method stub
        return null;
    }

    public String writeToFileStorage(String fileName, String content) {
        Log.i("TEST", "Writing in File: " + fileName + " data: " + content);
        try {
            fileNames.add(fileName);
            FileOutputStream outputStream = getContext().openFileOutput(fileName, Context.MODE_PRIVATE);
            outputStream.write(content.getBytes());
            outputStream.close();
            return "WRITECOMPLETE";
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    public String deleteFiles(String query) {
        Log.i("TEST", "DELETING QUERY in destination: " + query);
        if (query.equals("*")) {
            for (String fileName : fileNames) {
                getContext().deleteFile(fileName);
            }

            fileNames.clear();
        } else {
            getContext().deleteFile(query);
            fileNames.remove(query);
        }
        return "DELETECOMPLETE";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        // TODO Auto-generated method stub
        while (!replicationDone) {
            // Busy wait
        }

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

    private void replicateToNextNodes(Integer nodeIndex, Integer count, String message) {
        Log.i("TEST", "Replicating INSERT for " + count + " from node index: " + nodeIndex);
        Integer currIndex = nodeIndex;

        while (count > 0) {
            if (currIndex == nodeOrder.size())
                currIndex = 0;

            Log.i("TEST", "Replicating INSERT to " + nodeOrder.get(currIndex) + " Message: " + message);
            sendInsertMessage(nodeOrder.get(currIndex), message);
            currIndex++;
            count--;
        }
    }

    private void sendDeleteMessage(String port, String message) {
        String operation = "DELETE";
        String remotePort = Integer.toString(Integer.parseInt(port) * 2);

        String fullMessage = operation + "|" + message + "EOM\n";

        Log.i("TEST", "Sending " + fullMessage + " to " + port);
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

            do {
                ack = reader.readLine();
                count++;
            } while (count <= 20 && (ack == null || !ack.endsWith("EOM")));

            printWriter.close();
            reader.close();
            inputStreamReader.close();
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
    }

    private void sendInsertMessage(String port, String message) {
        String operation = "INSERT";
        String remotePort = Integer.toString(Integer.parseInt(port) * 2);

        String fullMessage = operation + "|" + message + "EOM\n";

        Log.i("TEST", "Sending " + fullMessage + " to " + port);
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

            do {
                ack = reader.readLine();
                count++;
            } while (count <= 20 && (ack == null || !ack.endsWith("EOM")));

            printWriter.close();
            reader.close();
            inputStreamReader.close();
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
    }

    private String sendQueryMessage(String port, String message, String operation) {
        String remotePort = Integer.toString(Integer.parseInt(port) * 2);

        String fullMessage = operation + "|" + message + "EOM\n";

        Log.i("TEST", "Sending " + fullMessage + " to " + port);
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

            ack = "";
            do {
                ack = reader.readLine();
                count++;
            } while (count <= 20 && (ack == null || !ack.endsWith("EOM")));


            printWriter.close();
            reader.close();
            inputStreamReader.close();
            socket.close();

            if (ack.endsWith("EOM"))
                return ack.substring(0, ack.length() - 3);

            return ack;
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

        return "";
    }

    private void insertOperation(String fileName, String content) {
        Log.i("TEST", "Received INSERT for " + fileName + " with data: " + content);
        String fileNameHash = genHash(fileName);
        // message format: fileName | content (no spaces)
        String message = fileName + "|" + content;

        int i = 0;
        String currNodeHash, preNodeHash;
        while (i < nodeOrder.size()) {
            if (i == 0) {
                currNodeHash = genHash(nodeOrder.get(i));
                preNodeHash = genHash(nodeOrder.get(nodeOrder.size() - 1));
            } else {
                currNodeHash = genHash(nodeOrder.get(i));
                preNodeHash = genHash(nodeOrder.get(i - 1));
            }


            if (preNodeHash.compareTo(currNodeHash) < 0) {
                // Normal ring

                if (preNodeHash.compareTo(fileNameHash) < 0 && currNodeHash.compareTo(fileNameHash) >= 0) {
                    break;
                }
            } else {
                // End ring case

                if (!(currNodeHash.compareTo(fileNameHash) < 0 && preNodeHash.compareTo(fileNameHash) >= 0)) {
                    break;
                }
            }

            i++;
        }

        replicateToNextNodes(i, 3, message);
    }

    @Override
    public boolean onCreate() {
        // TODO Auto-generated method stub
        // TODO: Remove this later, not needed
        nodeOrder = new LinkedList<String>();


        // Add all nodes in correct order
        nodeOrder.add("5562");
        nodeOrder.add("5556");
        nodeOrder.add("5554");
        nodeOrder.add("5558");
        nodeOrder.add("5560");

        fileNames = new HashSet<String>();
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

        replicationDone = false;
        new RecoveryTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);

        Log.i("TEST", "MY PORT: " + myPort);
        return false;
    }

    private void handleNodeReJoin() {
        Log.i("TEST", "Handling Node Rejoin");
        // Get My Data first
        int myIndex = nodeOrder.lastIndexOf(myPort);

        String successor1 = myIndex + 1 >= nodeOrder.size() ? nodeOrder.get(0) : nodeOrder.get(myIndex + 1);
        String successor2 = nodeOrder.lastIndexOf(successor1) + 1 >= nodeOrder.size() ? nodeOrder.get(0) : nodeOrder.get(nodeOrder.lastIndexOf(successor1) + 1);

        String response = sendQueryMessage(successor2, myPort, "GETSELF");

        Log.i("TEST", "Rejoin response from " + successor1 + " " + response);

        if (response.length() > 0) {
            for (String kvPair : response.split("\\+")) {
                String[] kvPairArr = kvPair.split(":");
                if (!fileNames.contains(kvPairArr[0]))
                    writeToFileStorage(kvPairArr[0], kvPairArr[1]);
            }
        }

        response = sendQueryMessage(successor1, myPort, "GETSELF");

        Log.i("TEST", "Rejoin response from " + successor2 + " " + response);

        if (response.length() > 0) {
            for (String kvPair : response.split("\\+")) {
                String[] kvPairArr = kvPair.split(":");
                if (!fileNames.contains(kvPairArr[0]))
                    writeToFileStorage(kvPairArr[0], kvPairArr[1]);
            }
        }

        // Get Others replication Data

        String prev1 = myIndex == 0 ? nodeOrder.get(nodeOrder.size() - 1) : nodeOrder.get(myIndex - 1);
        String prev2 = nodeOrder.lastIndexOf(prev1) == 0 ? nodeOrder.get(nodeOrder.size() - 1) : nodeOrder.get(nodeOrder.lastIndexOf(prev1) - 1);

        response = sendQueryMessage(successor1, prev1, "GETSELF");

        if (response.length() > 0) {
            for (String kvPair : response.split("\\+")) {
                String[] kvPairArr = kvPair.split(":");
                if (!fileNames.contains(kvPairArr[0]))
                    writeToFileStorage(kvPairArr[0], kvPairArr[1]);
            }
        }

        response = sendQueryMessage(prev1, prev1, "GETSELF");

        if (response.length() > 0) {
            for (String kvPair : response.split("\\+")) {
                String[] kvPairArr = kvPair.split(":");
                if (!fileNames.contains(kvPairArr[0]))
                    writeToFileStorage(kvPairArr[0], kvPairArr[1]);
            }
        }

        response = sendQueryMessage(prev1, prev2, "GETSELF");

        if (response.length() > 0) {
            for (String kvPair : response.split("\\+")) {
                String[] kvPairArr = kvPair.split(":");
                if (!fileNames.contains(kvPairArr[0]))
                    writeToFileStorage(kvPairArr[0], kvPairArr[1]);
            }
        }

        response = sendQueryMessage(prev2, prev2, "GETSELF");

        if (response.length() > 0) {
            for (String kvPair : response.split("\\+")) {
                String[] kvPairArr = kvPair.split(":");
                if (!fileNames.contains(kvPairArr[0]))
                    writeToFileStorage(kvPairArr[0], kvPairArr[1]);
            }
        }

        replicationDone = true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        // TODO Auto-generated method stub
        while (!replicationDone) {
            // Busy wait
        }

        Cursor cursor = new MatrixCursor(new String[]{"key", "value"});
        String selectionHash = genHash(selection);

        try {
            Log.i("TEST", "QUERY FOR " + selection);

            if (selection.equals("@")) {
                Log.i("TEST", "NOW QUERYING FOR @");
//                String[] fileList = getContext().fileList();

//                if (fileList != null && fileList.length > 0) {
                    for (String fileName : fileNames) {
                        InputStreamReader inputStreamReader = new InputStreamReader(getContext().openFileInput(fileName));
                        BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

                        ((MatrixCursor) cursor).newRow().add("key", fileName)
                                .add("value", bufferedReader.readLine());
                        bufferedReader.close();
                    }
//                }

            } else if (selection.equals("*")) {
                Log.i("TEST", "NOW QUERYING FOR *");

                for (String node : nodeOrder) {
                    String response = sendQueryMessage(node, selection, "SEARCH");

                    if (response.length() > 0) {

                        for (String kvPair : response.split("\\+")) {
                            String[] kvPairArr = kvPair.split(":");
                            ((MatrixCursor) cursor).newRow().add("key", kvPairArr[0])
                                    .add("value", kvPairArr[1]);
                        }
                    }
                }


            } else {
                Log.i("TEST", "NOW QUERYING FOR FILE NAME");
                if (fileNames.contains(selection)) {
                    InputStreamReader inputStreamReader = new InputStreamReader(getContext().openFileInput(selection));
                    BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

                    ((MatrixCursor) cursor).newRow().add("key", selection)
                            .add("value", bufferedReader.readLine());
                    bufferedReader.close();

                } else {
                    int i = 0;

                    String currNodeHash, preNodeHash;
                    while (i < nodeOrder.size()) {
                        if (i == 0) {
                            currNodeHash = genHash(nodeOrder.get(i));
                            preNodeHash = genHash(nodeOrder.get(nodeOrder.size() - 1));
                        } else {
                            currNodeHash = genHash(nodeOrder.get(i));
                            preNodeHash = genHash(nodeOrder.get(i - 1));
                        }


                        if (preNodeHash.compareTo(currNodeHash) < 0) {
                            // Normal ring

                            if (preNodeHash.compareTo(selectionHash) < 0 && currNodeHash.compareTo(selectionHash) >= 0) {
                                break;
                            }
                        } else {
                            // End ring case

                            if (!(currNodeHash.compareTo(selectionHash) < 0 && preNodeHash.compareTo(selectionHash) >= 0)) {
                                break;
                            }
                        }

                        i++;
                    }

                    String response = handleQuery(i, 3, selection);

                    ((MatrixCursor) cursor).newRow().add("key", selection)
                            .add("value", response.split("\\:")[1]);
                }
            }
        } catch (Exception e) {
            cursor.close();
            return null;
        }
        return cursor;
    }

    private String handleQuery(Integer nodeIndex, Integer count, String message) {
        Log.i("TEST", "Handling QUERY for " + count + " from node index: " + nodeIndex);
        Integer currIndex = nodeIndex;

        ArrayList<String> responses = new ArrayList<String>();
        while (count > 0) {
            if (currIndex == nodeOrder.size())
                currIndex = 0;

            responses.add(sendQueryMessage(nodeOrder.get(currIndex), message, "SEARCH"));
            currIndex++;
            count--;
        }

        return getCorrectResponse(responses);
    }

    private void handleDelete(Integer nodeIndex, Integer count, String message) {
        Log.i("TEST", "Handling DELETE for " + count + " from node index: " + nodeIndex);
        Integer currIndex = nodeIndex;

        while (count > 0) {
            if (currIndex == nodeOrder.size())
                currIndex = 0;

            sendDeleteMessage(nodeOrder.get(currIndex), message);
            currIndex++;
            count--;
        }
    }

    private String getCorrectResponse(ArrayList<String> responses) {
        Log.i("TEST", "Handling QUERY RESPONSES " + responses.toString());
        if (responses.get(0).length() > 0 && responses.get(0).equals(responses.get(1))) {
            Log.i("TEST", "Correct QUERY RESPONSE " + responses.get(0));
            return responses.get(0);
        } else if (responses.get(1).length() > 0 && responses.get(1).equals(responses.get(2))) {
            Log.i("TEST", "Correct QUERY RESPONSE " + responses.get(1));
            return responses.get(1);
        } else {
            Log.i("TEST", "Correct QUERY RESPONSE " + responses.get(2));
            return responses.get(2);
        }
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
