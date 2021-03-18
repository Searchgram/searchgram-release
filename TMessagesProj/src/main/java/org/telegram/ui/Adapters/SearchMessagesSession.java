package org.telegram.ui.Adapters;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import android.annotation.SuppressLint;
import android.text.TextUtils;
import android.util.SparseArray;
import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import org.telegram.messenger.*;
import org.telegram.messenger.Futures.UIThreadFuture;
import org.telegram.messenger.infra.AndroidFuture;
import org.telegram.messenger.support.ArrayUtils;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.ConnectionsManagerFutures.RequestFuture;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.TLRPC.Chat;
import org.telegram.tgnet.TLRPC.Message;

// a search session for results from fts and server with the same query
public class SearchMessagesSession {
	private static boolean debug = true && BuildVars.LOGS_ENABLED;

	public final int currentAccount;
	public final String query;
	public final int pageSize;

	// states for server search
	private MessageObject lastServerMessage = null;
	private boolean serverReachesEnd = false;
	private int nextSearchRate = 0;
	private int reqId = 0;

	// states for fts search
	private boolean ftsReachesEnd = false;

	private ArrayList<MessageObject> resultMessages = new ArrayList<>();
	private ArrayList<MessageObject> pendingMessages = new ArrayList<>();
	private Set<Long> seenMids = new HashSet<>();

	private AndroidFuture<Void> loadingFuture = null;

	public static final SearchMessagesSession EMPTY_SESSION = new SearchMessagesSession(-1, "",
                                                                                        20);

	public SearchMessagesSession(int currentAccount, @Nullable String query, int pageSize) {
		this.currentAccount = currentAccount;
		this.query = query == null ? "" : query;
		this.pageSize = pageSize;

		if (TextUtils.isEmpty(query)) {
			serverReachesEnd = true;
			ftsReachesEnd = true;
		}
	}

	public boolean isLoading() {
		return loadingFuture != null;
	}

	public boolean noMoreData() {
		return serverReachesEnd && ftsReachesEnd;
	}

	public ArrayList<MessageObject> copyResultMessages() {
		return new ArrayList<>(resultMessages);
	}

	public void cancelLoading() {
		if (reqId != 0) {
			ConnectionsManager.getInstance(currentAccount).cancelRequest(reqId, true);
			if (debug) { FileLog.w("cancelled reqId " + reqId + ", state " + debug()); }
			reqId = 0;
		}
		if (loadingFuture != null) {
			if (debug) { FileLog.w("future cancelled, state: " + debug()); }
			loadingFuture.cancel(true);
			loadingFuture = null;
		}
	}

	@MainThread
	public AndroidFuture<Void> loadMore() {
		if (loadingFuture != null) { cancelLoading(); }
		loadingFuture = loadMoreInternal().thenApply(__ -> {
			loadingFuture = null;
			return null;
		});
		return loadingFuture;
	}

	private AndroidFuture<Void> loadMoreInternal() {
		if (TextUtils.isEmpty(query)) {
			return UIThreadFuture.completedFuture(null);
		}

		if (pendingMessages.size() >= pageSize) {
			fillResults();

			return UIThreadFuture.completedFuture(null);
		}

		AndroidFuture<Void> serverFuture;

		if (serverReachesEnd) {
			serverFuture = UIThreadFuture.completedFuture(null);
		} else {
			TLRPC.TL_messages_searchGlobal req = new TLRPC.TL_messages_searchGlobal();
			req.limit = 20;
			req.q = query;
			req.filter = new TLRPC.TL_inputMessagesFilterEmpty();

			if (lastServerMessage == null) {
				req.offset_rate = 0;
				req.offset_id = 0;
				req.offset_peer = new TLRPC.TL_inputPeerEmpty();
			} else {
				req.offset_id = lastServerMessage.getId();
				req.offset_rate = nextSearchRate;
				int id;
				if (lastServerMessage.messageOwner.peer_id.channel_id != 0) {
					id = -lastServerMessage.messageOwner.peer_id.channel_id;
				} else if (lastServerMessage.messageOwner.peer_id.chat_id != 0) {
					id = -lastServerMessage.messageOwner.peer_id.chat_id;
				} else {
					id = lastServerMessage.messageOwner.peer_id.user_id;
				}
				req.offset_peer = MessagesController.getInstance(currentAccount).getInputPeer(id);
			}

			RequestFuture<TLRPC.messages_Messages, ArrayList<MessageObject>> requestFuture =
					ConnectionsManager.getInstance(currentAccount)
							.getFutures().sendRequest(req,
							                          this::processServerResponse);
			if (reqId != 0) {
				if (debug) { FileLog.e("reqId is not zero: " + reqId + ", query " + query); }
			}
			reqId = requestFuture.getReqId();

			if (debug) {
				FileLog.d("sending server request: " + req.offset_id + ", states:" + debug());
			}

			serverFuture = requestFuture.thenApply(responseObject -> {
				TLRPC.messages_Messages res = responseObject.res;
				MessagesStorage.getInstance(currentAccount).putUsersAndChats(res.users,
				                                                             res.chats,
				                                                             true, true);
				MessagesController.getInstance(currentAccount).putUsers(res.users,
				                                                        false);
				MessagesController.getInstance(currentAccount).putChats(res.chats,
				                                                        false);

				addToPendingMessages(responseObject.data);

				if (ArrayUtils.size(responseObject.data) > 0) {
					lastServerMessage = responseObject.data.get(responseObject.data.size() - 1);
				}
				nextSearchRate = res.next_rate;
				serverReachesEnd = res.messages.size() != 20;
				reqId = 0;

				if (debug) {
					FileLog.d("server results: " + res.messages.size() + ", states " + debug());
				}

				return null;
			});
		}

		return serverFuture.thenCompose(__ -> {
			if (ftsReachesEnd) { return UIThreadFuture.completedFuture(new ArrayList<>()); }

			int minDate = 0;
			if (!serverReachesEnd) { minDate = lastServerMessage.messageOwner.date; }
			return MessagesStorage.getInstance(currentAccount).getFutures().ftsSearch(query, minDate,
			                                                                          Integer.MAX_VALUE,
			                                                                          seenMids, pageSize);
		}).thenApply(messageObjects -> {
			if (debug) {
				FileLog.d("fts result: " + messageObjects.size() + ", states " + debug());
			}
			addToPendingMessages(messageObjects);
			ftsReachesEnd = serverReachesEnd && messageObjects.size() < pageSize;

			fillResults();
			return null;
		});
	}

	@SuppressLint("DefaultLocale")
	public String debug() {
		return String.format(
				"(currentAccount %d, query %s, reqId %d, serverReachesEnd %s, ftsReachesEnd %s, resultMessages %d, pending %d, loading %s)",
				currentAccount, query, reqId, serverReachesEnd, ftsReachesEnd, resultMessages.size(),
				pendingMessages.size(), isLoading());
	}

	private ArrayList<MessageObject> processServerResponse(TLRPC.messages_Messages res) {
		final ArrayList<MessageObject> messageObjects = new ArrayList<>();
		SparseArray<Chat> chatsMap = new SparseArray<>();
		SparseArray<TLRPC.User> usersMap = new SparseArray<>();
		for (int a = 0; a < res.chats.size(); a++) {
			Chat chat = res.chats.get(a);
			chatsMap.put(chat.id, chat);
		}
		for (int a = 0; a < res.users.size(); a++) {
			TLRPC.User user = res.users.get(a);
			usersMap.put(user.id, user);
		}
		for (int a = 0; a < res.messages.size(); a++) {
			Message message = res.messages.get(a);
			MessageObject messageObject = new MessageObject(currentAccount, message, usersMap, chatsMap,
			                                                false, true);
			messageObjects.add(messageObject);
			messageObject.setQuery(query);
		}
		sort(messageObjects);
		return messageObjects;
	}

	public static void sort(ArrayList<MessageObject> messageObjects) {
		messageObjects.sort((o1, o2) -> o2.messageOwner.date - o1.messageOwner.date);
	}

	private void addToPendingMessages(List<MessageObject> messageObjects) {
		if (messageObjects == null) { return; }
		int previous = pendingMessages.size();
		for (int a = 0; a < messageObjects.size(); a++) {
			MessageObject mo = messageObjects.get(a);
			Message message = mo.messageOwner;
			long did = MessageObject.getDialogId(message);
			Integer maxId = MessagesController.getInstance(
					currentAccount).deletedHistory.get(did);
			seenMids.add(mo.getIdWithChannel());

			if (maxId != null && message.id <= maxId) { // skip deleted messages
				continue;
			}
			pendingMessages.add(mo);
			long dialog_id = MessageObject.getDialogId(message);
			ConcurrentHashMap<Long, Integer> read_max =
					message.out ? MessagesController.getInstance(
							currentAccount).dialogs_read_outbox_max :
					MessagesController.getInstance(
							currentAccount).dialogs_read_inbox_max;
			Integer value = read_max.get(dialog_id);
			if (value == null) {
				value = MessagesStorage.getInstance(currentAccount)
						.getDialogReadMax(message.out, dialog_id);
				read_max.put(dialog_id, value);
			}
			message.unread = value < message.id;
		}
		sort(pendingMessages);
		if (debug) {
			FileLog.d("added " + (pendingMessages.size() - previous) + ", states " + debug());
		}
	}

	private void fillResults() {
		int min = Math.min(pageSize, pendingMessages.size());
		List<MessageObject> subList = pendingMessages.subList(0, min);
		resultMessages.addAll(subList);
		pendingMessages.removeAll(subList);
		if (debug) { FileLog.d("filled " + min + ", states " + debug()); }
	}
}
