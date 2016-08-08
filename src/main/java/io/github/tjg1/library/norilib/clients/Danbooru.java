/*
 * This file is part of nori.
 * Copyright (c) 2014 Tomasz Jan Góralczyk <tomg@fastmail.uk>
 * License: ISC
 */

package io.github.tjg1.library.norilib.clients;

import android.net.Uri;
import android.os.AsyncTask;
import android.util.Base64;

import io.github.tjg1.library.norilib.Image;
import io.github.tjg1.library.norilib.SearchResult;
import io.github.tjg1.library.norilib.Tag;
import com.squareup.okhttp.Authenticator;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseBody;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.StringReader;
import java.net.Proxy;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Client for the Danbooru 2.x API.
 */
public class Danbooru implements SearchClient {
  /**
   * Number of images per search results page.
   * Best to use a large value to minimize number of unique HTTP requests.
   */
  private static final int DEFAULT_LIMIT = 100;
  /** Thumbnail size set if not returned by the API. */
  private static final int THUMBNAIL_SIZE = 150;
  /** Sample size set if not returned by the API. */
  private static final int SAMPLE_SIZE = 850;
  /** Parser used to read the date format used by this API. */
  private static final DateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
  /** OkHTTP Client. */
  private final OkHttpClient okHttpClient = new OkHttpClient();
  /** Human-readable service name */
  private final String name;
  /** URL to the HTTP API Endpoint - the server implementing the API. */
  private final String apiEndpoint;
  /** Username used for authentication. (optional) */
  private final String username;
  /** API key used for authentication. (optional) */
  private final String apiKey;

  /**
   * Create a new Danbooru 2.x client without authentication.
   *
   * @param name     Human-readable service name.
   * @param endpoint URL to the HTTP API Endpoint - the server implementing the API.
   */
  public Danbooru(String name, String endpoint) {
    this.name = name;
    this.apiEndpoint = endpoint;
    this.username = null;
    this.apiKey = null;
  }

  /**
   * Create a new Danbooru 1.x client with authentication.
   *
   * @param name     Human-readable service name.
   * @param endpoint URL to the HTTP API Endpoint - the server implementing the API.
   * @param username Username used for authentication.
   * @param apiKey   API key used for authentication.
   */
  public Danbooru(String name, String endpoint, String username, final String apiKey) {
    this.name = name;
    this.apiEndpoint = endpoint;
    this.username = username;
    this.apiKey = apiKey;


    /*
    * This doesn't work as far as I can tell.  /Kycklingar
    * The api accepts the parameters "login" and "api_key"
    *
    * // Enable HTTP Basic Authentication.
    *
    * okHttpClient.setAuthenticator(new Authenticator() {
    *  @Override
    *  public Request authenticate(Proxy proxy, Response response) throws IOException {
    *    final String credential = Base64.encodeToString(String.format("%s:%s", Danbooru.this.username, Danbooru.this.apiKey).getBytes(), Base64.DEFAULT);
    *    return response.request().newBuilder().header("Authorization", credential).build();
    *  }
    *
    *  @Override
    *  public Request authenticateProxy(Proxy proxy, Response response) throws IOException {
    *    return null;
    *  }
    *});
    */
  }

  @Override
  public SearchResult search(String tags) throws IOException {
    // Return results for page 0.
    return search(tags, 0);
  }

  @Override
  public SearchResult search(String tags, int pid) throws IOException {
    // Create HTTP request.
    final Request request = new Request.Builder()
        .url(createSearchURL(tags, pid, DEFAULT_LIMIT) + String.format("&login=%s&api_key=%s", Danbooru.this.username, Danbooru.this.apiKey))// Maybe not ideal but it works.
        .build();
    // Get HTTP response.
    final Response response = okHttpClient.newCall(request).execute();
    final ResponseBody responseBody = response.body();
    final String body = responseBody.string();
    responseBody.close();

    // Return parsed SearchResult.
    return parseXMLResponse(body, tags, pid);
  }

  @Override
  public void search(String tags, SearchCallback callback) {
    // Return results for page 0.
    search(tags, 0, callback);
  }

  @Override
  public void search(final String tags, final int pid, final SearchCallback callback) {
    // Fetch results on a background thread.
    new AsyncTask<Void, Void, SearchResult>() {
      /** Error returned when attempting to fetch the SearchResult. */
      private IOException error;

      @Override
      protected SearchResult doInBackground(Void... voids) {
        try {
          return Danbooru.this.search(tags, pid);
        } catch (IOException e) {
          // Hold on to the error for now and handle it on the main UI thread in #postExecute().
          error = e;
        }
        return null;
      }

      @Override
      protected void onPostExecute(SearchResult searchResult) {
        // Pass the result or error to the SearchCallback.
        if (error != null || searchResult == null) {
          callback.onFailure(error);
        } else {
          callback.onSuccess(searchResult);
        }
      }
    }.execute();
  }

  /**
   * Parse an XML response returned by the API.
   *
   * @param body   HTTP Response body.
   * @param tags   Tags used to retrieve the response.
   * @param offset Current paging offset.
   * @return A {@link io.github.tjg1.library.norilib.SearchResult} parsed from given XML.
   */
  @SuppressWarnings("FeatureEnvy")
  protected SearchResult parseXMLResponse(String body, String tags, int offset) throws IOException {
    // Create variables to hold the values as XML is being parsed.
    final List<Image> imageList = new ArrayList<>(DEFAULT_LIMIT);
    Image image = new Image();
    List<Tag> imageTags = new ArrayList<>();
    int position = 0;

    try {
      // Create an XML parser factory and disable namespace awareness for security reasons.
      // See: (http://lists.w3.org/Archives/Public/public-xmlsec/2009Dec/att-0000/sws5-jensen.pdf).
      final XmlPullParserFactory xmlParserFactory = XmlPullParserFactory.newInstance();
      xmlParserFactory.setNamespaceAware(false);

      // Create a new XML parser from factory and feed HTTP response data into it.
      final XmlPullParser xpp = xmlParserFactory.newPullParser();
      xpp.setInput(new StringReader(body));

      // Iterate over each XML element and handle pull parser "events".
      while (xpp.getEventType() != XmlPullParser.END_DOCUMENT) {
        if (xpp.getEventType() == XmlPullParser.START_TAG) {
          // Get the tag's name.
          final String name = xpp.getName();

          if ("post".equals(name)) {
            // Create a new image for each <post> tag.
            image = new Image();
            imageTags = new ArrayList<>();
            image.searchPage = offset;
            image.searchPagePosition = position;
          }
          // Extract image metadata from XML tags.
          else if ("large-file-url".equals(name)) {
            image.fileUrl = apiEndpoint + xpp.nextText();
          } else if ("image-width".equals(name)) {
            image.width = Integer.parseInt(xpp.nextText());
          } else if ("image-height".equals(name)) {
            image.height = Integer.parseInt(xpp.nextText());
          } else if ("preview-file-url".equals(name)) {
            image.previewUrl = apiEndpoint + xpp.nextText();
          } else if ("file-url".equals(name)) {
            image.sampleUrl = apiEndpoint + xpp.nextText();
          } else if ("tag-string-general".equals(name)) {
            imageTags.addAll(Arrays.asList(Tag.arrayFromString(xpp.nextText(), Tag.Type.GENERAL)));
          } else if ("tag-string-artist".equals(name)) {
            imageTags.addAll(Arrays.asList(Tag.arrayFromString(xpp.nextText(), Tag.Type.ARTIST)));
          } else if ("tag-string-character".equals(name)) {
            imageTags.addAll(Arrays.asList(Tag.arrayFromString(xpp.nextText(), Tag.Type.CHARACTER)));
          } else if ("tag-string-copyright".equals(name)) {
            imageTags.addAll(Arrays.asList(Tag.arrayFromString(xpp.nextText(), Tag.Type.COPYRIGHT)));
          } else if ("id".equals(name)) {
            image.id = xpp.nextText();
          } else if ("parent-id".equals(name)) {
            image.parentId = xpp.getAttributeValue(null, "nil") != null ? null : xpp.nextText();
          } else if ("pixiv-id".equals(name)) {
            image.pixivId = xpp.getAttributeValue(null, "nil") != null ? null : xpp.nextText();
          } else if ("rating".equals(name)) {
            image.obscenityRating = Image.ObscenityRating.fromString(xpp.nextText());
          } else if ("score".equals(name)) {
            image.score = Integer.parseInt(xpp.nextText());
          } else if ("source".equals(name)) {
            image.source = xpp.nextText();
          } else if ("md5".equals(name)) {
            image.md5 = xpp.nextText();
          } else if ("created-at".equals(name)) {
            image.createdAt = DATE_FORMAT.parse(xpp.nextText());
          }
          // createdAt
        } else if (xpp.getEventType() == XmlPullParser.END_TAG) {
          if ("post".equals(xpp.getName())) {
            // Convert tag list to array.
            image.tags = imageTags.toArray(new Tag[imageTags.size()]);
            // Append values not returned by API to image.
            image.webUrl = webUrlFromId(image.id);
            // FIXME: API does not return thumbnail sizes.
            image.previewWidth = THUMBNAIL_SIZE;
            image.previewHeight = THUMBNAIL_SIZE;
            // FIXME: API does not return sample sizes.
            image.sampleWidth = SAMPLE_SIZE;
            image.sampleHeight = SAMPLE_SIZE;
            // Discard images requiring a gold account. They do not return a valid file_url.
            if (image.fileUrl != null) {
              // Add to result.
              imageList.add(image);
              position++;
            }
          }
        }
        xpp.next();
      }
    } catch (XmlPullParserException | ParseException e) {
      // Convert into IOException.
      // Needed for consistent method signatures in the SearchClient interface for different APIs.
      // (Throwing an XmlPullParserException would be fine, until dealing with an API using JSON, etc.)
      throw new IOException(e);
    }

    return new SearchResult(imageList.toArray(new Image[imageList.size()]), Tag.arrayFromString(tags), offset);
  }

  /**
   * Generate request URL to the search API endpoint.
   *
   * @param tags  Space-separated tags.
   * @param pid   Page number (0-indexed).
   * @param limit Images to fetch per page.
   * @return URL to search results API.
   */
  protected String createSearchURL(String tags, int pid, int limit) {
    // Page numbers are 1-indexed for this API.
    final int page = pid + 1;

    return String.format(Locale.US, apiEndpoint + "/posts.xml?tags=%s&page=%d&limit=%d", Uri.encode(tags), page, limit);
  }

  /**
   * Get a URL viewable in the system web browser for given Image ID.
   *
   * @param id {@link io.github.tjg1.library.norilib.Image} ID.
   * @return URL for viewing the image in the browser.
   */
  protected String webUrlFromId(String id) {
    return String.format(Locale.US, "%s/posts/%s", apiEndpoint, id);
  }

  @Override
  public String getDefaultQuery() {
    // Show work-safe images by default.
    return "";
  }

  @Override
  public Settings getSettings() {
    return new Settings(Settings.APIType.DANBOORU, name, apiEndpoint, username, apiKey);
  }

  @Override
  public AuthenticationType requiresAuthentication() {
    return AuthenticationType.OPTIONAL;
  }
}
