/*
	This file is part of ServerProxy.
	SocketProxy is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.
	Subsonic is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
	GNU General Public License for more details.
	You should have received a copy of the GNU General Public License
	along with Subsonic. If not, see <http://www.gnu.org/licenses/>.
	Copyright 2014 (C) Scott Jackson
*/

package github.daneren2005.serverproxy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.ByteOrder;
import java.util.StringTokenizer;

import org.apache.http.Header;
import org.apache.http.HttpRequest;
import org.apache.http.message.BasicHttpRequest;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

public abstract class ServerProxy implements Runnable {
	private static final String TAG = ServerProxy.class.getSimpleName();

	private Thread thread;
	protected boolean isRunning;
	private ServerSocket socket;
	private int port;
	private Context context;

	public ServerProxy(Context context) {
		// Create listening socket
		try {
			socket = new ServerSocket(0, 0, InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }));
			socket.setSoTimeout(5000);
			port = socket.getLocalPort();
			this.context = context;
		} catch (UnknownHostException e) { // impossible
		} catch (IOException e) {
			Log.e(TAG, "IOException initializing server", e);
		}
	}

	public void start() {
		thread = new Thread(this, "Socket Proxy");
		thread.start();
	}

	public void stop() {
		isRunning = false;
		thread.interrupt();
	}

	public String getPrivateAddress(String request) {
		try {
			return getAddress("127.0.0.1", URLEncoder.encode(request, "UTF-8"));
		} catch (UnsupportedEncodingException e) {
			return null;
		}
	}
	public String getPublicAddress(String request) {
		WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
		int ipAddress = wifiManager.getConnectionInfo().getIpAddress();

		if (ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN)) {
			ipAddress = Integer.reverseBytes(ipAddress);
		}

		byte[] ipByteArray = BigInteger.valueOf(ipAddress).toByteArray();
		String ipAddressString = null;
		try {
			ipAddressString = InetAddress.getByAddress(ipByteArray).getHostAddress();
		} catch(UnknownHostException ex) {
			Log.e(TAG, "Unable to get host address.");
		}

		return getAddress(ipAddressString, request);
	}
	private String getAddress(String host, String request) {
		return String.format("http://%s:%d/%s", host, port, request);
	}

	@Override
	public void run() {
		isRunning = true;
		while (isRunning) {
			try {
				Socket client = socket.accept();
				if (client == null) {
					continue;
				}
				Log.i(TAG, "client connected");

				ProxyTask task = getTask(client);
				if (task.processRequest()) {
					new Thread(task, "ProxyTask").start();
				}

			} catch (SocketTimeoutException e) {
				// Do nothing
			} catch (IOException e) {
				Log.e(TAG, "Error connecting to client", e);
			}
		}
		Log.i(TAG, "Proxy interrupted. Shutting down.");
	}

	abstract ProxyTask getTask(Socket client);

	protected abstract class ProxyTask implements Runnable {
		protected Socket client;
		protected String path;
		protected int cbSkip = 0;

		public ProxyTask(Socket client) {
			this.client = client;
		}

		protected HttpRequest readRequest() {
			HttpRequest request = null;
			InputStream is;
			String firstLine;
			BufferedReader reader = null;
			try {
				is = client.getInputStream();
				reader = new BufferedReader(new InputStreamReader(is), 8192);
				firstLine = reader.readLine();
			} catch (IOException e) {
				Log.e(TAG, "Error parsing request", e);
				return request;
			}

			if (firstLine == null) {
				Log.i(TAG, "Proxy client closed connection without a request.");
				return request;
			}

			StringTokenizer st = new StringTokenizer(firstLine);
			String method = st.nextToken();
			String uri = st.nextToken();
			String realUri = uri.substring(1);
			request = new BasicHttpRequest(method, realUri);

			// Get all of the headers
			try {
				String line;
				while((line = reader.readLine()) != null && !"".equals(line)) {
					String headerName = line.substring(0, line.indexOf(':'));
					String headerValue = line.substring(line.indexOf(": ") + 2);
					request.addHeader(headerName, headerValue);
				}
			} catch(IOException e) {
				// Don't really care once past first line
			}

			return request;
		}

		public boolean processRequest() {
			HttpRequest request = readRequest();
			if (request == null) {
				return false;
			}

			// Read HTTP headers
			try {
				path = URLDecoder.decode(request.getRequestLine().getUri(), "UTF-8");
			} catch (UnsupportedEncodingException e) {
				Log.e(TAG, "Unsupported encoding", e);
				return false;
			}

			Log.i(TAG, "Processing request for " + path);

			// Try to get range requested
			Header rangeHeader = request.getFirstHeader("Range");

			if(rangeHeader != null) {
				String range = rangeHeader.getValue();
				int index = range.indexOf("=");
				if(index >= 0) {
					range = range.substring(index + 1);

					index = range.indexOf("-");
					if(index > 0) {
						range = range.substring(0, index);
					}

					cbSkip = Integer.parseInt(range);
				}
			}

			return true;
		}
	}
}
