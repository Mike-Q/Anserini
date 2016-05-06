package io.anserini.rts;

import io.anserini.document.twitter.Status;
import io.anserini.nrts.TweetSearcher;

import java.io.IOException;
import java.util.Calendar;
import java.util.TimeZone;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.IntField;
import org.apache.lucene.document.LongField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.NumericRangeQuery;
import org.apache.lucene.search.Query;

import twitter4j.RawStreamListener;
import twitter4j.TwitterStream;
import twitter4j.TwitterStreamFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class TRECIndexerRunnable implements Runnable {
	private static final Logger LOG = TRECIndexer.LOG;
	public IndexWriter indexWriter = TweetSearcher.indexWriter;

	private static final JsonParser JSON_PARSER = new JsonParser();

	public TRECIndexerRunnable(IndexWriter indexWriter) {
		this.indexWriter = indexWriter;
	}

	public static enum StatusField {
		ID("id"), SCREEN_NAME("screen_name"), EPOCH("epoch"), TEXT("text"), LANG("lang"), IN_REPLY_TO_STATUS_ID(
				"in_reply_to_status_id"), IN_REPLY_TO_USER_ID("in_reply_to_user_id"), FOLLOWERS_COUNT(
						"followers_count"), FRIENDS_COUNT("friends_count"), STATUSES_COUNT(
								"statuses_count"), RETWEETED_STATUS_ID("retweeted_status_id"), RETWEETED_USER_ID(
										"retweeted_user_id"), RETWEET_COUNT("retweet_count"), RAW_TEXT(
												"raw_text"), NAME("name"), PROFILE_IMAGE_URL("profile_image_url");

		public final String name;

		StatusField(String s) {
			name = s;
		}
	};

	public static int tweetCount;
	public boolean isRunning = true;
	public String startTime = "";
	public String endTime="";
	public TwitterStream twitterStream;
	
	public void terminate() {
		twitterStream.cleanUp();
		endTime=Calendar.getInstance(TimeZone.getTimeZone("UTC")).getTime().toGMTString();
	}

	@Override
	public void run() {
		startTime = Calendar.getInstance(TimeZone.getTimeZone("UTC")).getTime().toGMTString();
		tweetCount = 0;

		final FieldType textOptions = new FieldType();
		textOptions.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
		textOptions.setStored(true);
		textOptions.setTokenized(true);

		twitterStream = new TwitterStreamFactory().getInstance();
		RawStreamListener rawListener = new RawStreamListener() {

			@Override
			public void onMessage(String rawString) {
				Status status = Status.fromJson(rawString);
				// TREC 2015 IMPORTANT: Treatment of retweets.
				if (status.getRetweetStatusString() != null) {
					status = Status.fromJson(status.getRetweetStatusString());
				}

				if (status == null) {
					try {
						JsonObject obj = (JsonObject) JSON_PARSER.parse(rawString);
						if (obj.has("delete")) {
							long id = obj.getAsJsonObject("delete").getAsJsonObject("status").get("id").getAsLong();
							Query q = NumericRangeQuery.newLongRange(StatusField.ID.name, id, id, true, true);
							indexWriter.deleteDocuments(q);
							// LOG.info("Deleted a document: " + id);
						}
					} catch (Exception e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					return;
				}

				// filter out non-english tweet
				if (!status.getLang().equals("en")) {
					return;
				}

				if (status.getText() == null) {
					return;
				}

				// filter out retweets
				if (status.getText().substring(0, 4).equals("RT @")) {
					return;
				}

				// pre-process raw tweet text with TRECTwokenizer
				String rawText = status.getText();
				String processedRawText = rawText.replaceAll("[^\\x00-\\x7F]", "");

				if (processedRawText == null) {
					return;
				}

				String whiteSpaceTokenizedText = TRECTwokenizer.trecTokenizeText(processedRawText);
				if (whiteSpaceTokenizedText == "") {
					return;
				}
				// LOG.info("whiteSpaceTokenizedText: " +
				// whiteSpaceTokenizedText);
				Document doc = new Document();
				doc.add(new LongField(StatusField.ID.name, status.getId(), Field.Store.YES));
				doc.add(new LongField(StatusField.EPOCH.name, status.getEpoch(), Field.Store.YES));
				doc.add(new TextField(StatusField.SCREEN_NAME.name, status.getScreenname(), Store.YES));
				doc.add(new TextField(StatusField.NAME.name, status.getName(), Store.YES));
				doc.add(new TextField(StatusField.PROFILE_IMAGE_URL.name, status.getProfileImageURL(), Store.YES));

				// doc.add(new Field(StatusField.TEXT.name, status.getText(),
				// textOptions));
				doc.add(new Field(StatusField.TEXT.name, whiteSpaceTokenizedText, textOptions));
				// doc.add(new IntField(StatusField.FRIENDS_COUNT.name,
				// status.getFollowersCount(), Store.YES));
				// doc.add(new IntField(StatusField.FOLLOWERS_COUNT.name,
				// status.getFriendsCount(), Store.YES));
				// doc.add(new IntField(StatusField.STATUSES_COUNT.name,
				// status.getStatusesCount(), Store.YES));
				doc.add(new TextField(StatusField.RAW_TEXT.name, status.getText(), Store.YES));

				// long inReplyToStatusId = status.getInReplyToStatusId();
				// if (inReplyToStatusId > 0) {
				// doc.add(new LongField(StatusField.IN_REPLY_TO_STATUS_ID.name,
				// inReplyToStatusId, Field.Store.YES));
				// doc.add(new LongField(StatusField.IN_REPLY_TO_USER_ID.name,
				// status.getInReplyToUserId(),
				// Field.Store.YES));
				// }

				// String lang = status.getLang();
				// if (!lang.equals("unknown")) {
				// doc.add(new TextField(StatusField.LANG.name,
				// status.getLang(), Store.YES));
				// }

				long retweetStatusId = status.getRetweetedStatusId();
				if (retweetStatusId > 0) {
					// doc.add(new
					// LongField(StatusField.RETWEETED_STATUS_ID.name,
					// retweetStatusId, Field.Store.YES));
					// doc.add(new LongField(StatusField.RETWEETED_USER_ID.name,
					// status.getRetweetedUserId(),
					// Field.Store.YES));
					doc.add(new IntField(StatusField.RETWEET_COUNT.name, status.getRetweetCount(), Store.YES));
					if (status.getRetweetCount() < 0 || status.getRetweetedStatusId() < 0) {
						System.err.println("Error parsing retweet fields of " + status.getId());
					}
				}

				try {
					indexWriter.addDocument(doc);
					indexWriter.commit();
					tweetCount++;
					if (tweetCount % 1000 == 0) {
						LOG.info(tweetCount + " statuses indexed");
					}
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}

			}

			@Override
			public void onException(Exception e) {
				// TODO Auto-generated method stub
				e.printStackTrace();
			}
		};

		twitterStream.addListener(rawListener);
		twitterStream.sample();
		
	}
}
