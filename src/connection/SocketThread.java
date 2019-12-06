package connection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Creates socket thread for listening
 * @author Karol Cagáň
 * @version 1.0
 */
public class SocketThread extends Thread
{
  public volatile boolean running = true;
  private Socket socket;
  private OutputStream outStream = null;
  private InputStream inStream = null;
  private ProcessThread processThread = null;
  private String hWIdentifier;
  private int internalIdentifier;

  /**
   * Constructor for new instance
   * @param socket
   * @param listener
   * @param id
   */
  public SocketThread(Socket socket, SocketListener listener, int id) {
    this.socket = socket;
    this.processThread = new ProcessThread(listener, this);
    this.internalIdentifier = id;
    System.out.println("New AP listener created for AP of internal ID: " + this.internalIdentifier);
  }

  @Override
  public void run() {
    super.run();

    // Starting message processing thread
    new Thread(processThread).start();
    // While running listens for incomming messages

    while (running && socket.isConnected()) {
      try {
        String inData = this.read();
        //System.out.println(inData);
        processThread.putToQueue(inData);
      } catch (IOException e) {
        e.printStackTrace();
        break;
      } catch (InterruptedException e) {
        e.printStackTrace();
        break;
      }
    }

    // Turn off function
    try {
      processThread.running = false;
      processThread.putToQueue("ENDING");
      outStream.close();
      inStream.close();
      socket.close();
      this.processThread.listener.socketDown(internalIdentifier);
    } catch (IOException e) {
      e.printStackTrace();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  /**
   * Sends downlink message to AP
   * @param jsonText
   * @throws IOException
   */
  public void write(String jsonText) throws IOException {
    outStream = socket.getOutputStream();
    outStream.write(jsonText.getBytes(Charset.forName("UTF-8")));
  }

  /**
   * hWIdentifier serves for downlink AP identification
   * @return String
   */
  public String gethWIdentifier() {
    return hWIdentifier;
  }

  /**
   * @param hWIdentifier
   */
  public void sethWIdentifier(String hWIdentifier) {
    this.hWIdentifier = hWIdentifier;
  }

  /**
   * Reads data from buffer, returns JSON string
   */
  private String read() throws IOException {
    inStream = socket.getInputStream();
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    int length;

    while (true) {
      length = inStream.read(buffer);
      if (length <= 0) {
        throw new IOException("Connection has been closed");
      }
      result.write(buffer, 0, length);
      if (inStream.available() == 0) {
        break;
      }
    }

    return result.toString("UTF-8");
  }

  /**
   * Unprocessed messages wain in a quee until server is ready to accept them
   */
  private class ProcessThread implements Runnable {
    private volatile boolean running = true;
    private LinkedBlockingQueue<String> jobQueue;
    private SocketListener listener;
    private SocketThread parent;

    public ProcessThread(SocketListener listener, SocketThread parent) {
      this.listener = listener;
      this.parent = parent;
      this.jobQueue = new LinkedBlockingQueue<>();
    }

    public void putToQueue(String insert) throws InterruptedException {
      this.jobQueue.put(insert);
    }

    @Override
    public void run() {
      System.out.println("Starting process thread");
      String inData = null;

      while (running) {
        try {
          inData = jobQueue.take();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
        // NETWORK DOWN
        if (running==false){
          break; // lasts messages + ENDING message
        }
        this.listener.process(parent, inData, running, internalIdentifier);
      }

      // Powerdown function
      if (!running) {
        int leftover = jobQueue.size() - 1; // REMOVE ENDING message
        for (int i =0; i<leftover; i++) {
          inData = jobQueue.poll();
          this.listener.process(parent, inData, false, internalIdentifier);
        }
        return;
      }
    }
  }
}