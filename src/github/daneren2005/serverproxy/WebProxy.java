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

import android.content.Context;
import android.util.Log;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public class WebProxy extends ServerProxy {
	private static String TAG = WebProxy.class.getSimpleName();
	private static List REMOVE_REQUEST_HEADERS = Arrays.asList("Host", "Accept-Encoding", "Referer");
	private static List REMOVE_RESPONSE_HEADERS = Arrays.asList("Transfer-Encoding");
	private HttpClient httpClient;

	public WebProxy(Context context) {
		super(context);
	}
	public WebProxy(Context context, HttpClient httpClient) {
		super(context);

		this.httpClient = httpClient;
	}

	@Override
	ProxyTask getTask(Socket client) {
		return new StreamSiteTask(client);
	}

	protected class StreamSiteTask extends ProxyTask {
		private List<Header> headers;

		public StreamSiteTask(Socket client) {
			super(client);
		}

		public List<Header> getHeaders(List<Header> headers) {
			// Strip common problems
			Iterator<Header> it = headers.iterator();
			while(it.hasNext()) {
				Header header = it.next();
				if("Server".equals(header.getName())) {
					it.remove();
				}
			}

			return headers;
		}
		private String getHeaderString(int response, List<Header> headers) {
			StringBuilder sb = new StringBuilder();

			sb.append("HTTP/1.0 ");
			sb.append(response);
			sb.append(" OK\r\n");

			boolean addContentType = true;
			for(Header header: headers) {
				if(REMOVE_RESPONSE_HEADERS.contains(header.getName())) {
					continue;
				}

				sb.append(header.getName());
				sb.append(": ");

				// Make sure that connection is close, not keep-alive
				if("Connection".equals(header.getName())) {
					sb.append("close");
				} else {
					sb.append(header.getValue());
				}

				if("Content-Type".equals(header.getName())) {
					addContentType = false;
				}

				sb.append("\r\n");
			}
			if(addContentType) {
				sb.append("Content-Type: application/octet-stream\r\n");
			}
			sb.append("\r\n");

			return sb.toString();
		}

		@Override
		public void run() {
			HttpClient httpClient;
			if(WebProxy.this.httpClient != null) {
				httpClient = WebProxy.this.httpClient;
			} else {
				httpClient = new DefaultHttpClient();
			}
			HttpResponse response;
			try {
                HttpPost newRequest = new HttpPost(path);
				for(Header header: request.getAllHeaders()) {
					if(!REMOVE_REQUEST_HEADERS.contains(header.getName()) && !(header.getName().equals("Content-Length") && header.getValue().equals("0"))  ) {
						newRequest.addHeader(header);
					}
				}

				response = httpClient.execute(newRequest);
				StatusLine status = response.getStatusLine();
				if(status.getStatusCode() == HttpStatus.SC_OK || status.getStatusCode() == HttpStatus.SC_PARTIAL_CONTENT) {
					headers = new ArrayList<Header>();
					headers.addAll(Arrays.asList(response.getAllHeaders()));
					headers = getHeaders(headers);

					OutputStream output = null;
					InputStream input = null;
					try {
						output = new BufferedOutputStream(client.getOutputStream(), 64*1024);
						output.write(getHeaderString(response.getStatusLine().getStatusCode(), headers).getBytes());

						HttpEntity entity = response.getEntity();
						if(entity == null) {
							Log.w(TAG, "Failed to get entity for request: " + path);
							return;
						}
						input = entity.getContent();

						byte[] buffer = new byte[1024 * 32];
						int count = 0, n= 0;
						while (-1 != (n = input.read(buffer))) {
							output.write(buffer, 0, n);
							count += n;
						}
						output.flush();
					} finally {
						try {
							if (output != null) {
								output.close();
							}
						} catch(Exception e) {
							Log.w(TAG, "Error closing output stream");
						}

						try {
							if(input != null) {
								input.close();
							}
						} catch(Exception e) {
							Log.w(TAG, "Error closing input stream");
						}
					}
				} else {
					response.getEntity().getContent().close();
					throw new IOException(status.getReasonPhrase());
				}
			} catch (IOException e) {
				Log.e(TAG, "Failed to get data from url: " + path, e);
			} catch(Exception e) {
				Log.e(TAG, "Exception thrown from web proxy task", e);
			}
		}
	}
}
