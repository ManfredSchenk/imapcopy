package com.fisbein.joan.model;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.URLName;

import org.apache.commons.collections4.ListUtils;
import org.apache.commons.collections4.Predicate;
import org.apache.log4j.Logger;

public class ImapCopier implements Runnable {
	private final static Logger log = Logger.getLogger(ImapCopier.class);

	private Store sourceStore = null;

	private Store targetStore = null;
	
	private Store targetStoreForCheck = null;

	private HashSet<String> alreadyCopied = new HashSet<String>();

	private List<ImapCopyListenerInterface> listeners = new ArrayList<ImapCopyListenerInterface>(0);

	private List<String> filteredFolders = new ArrayList<String>();
	
	private int endYear;

	public static void main(String[] args) throws MessagingException {
		log.info("Starting");
		log.debug("Parameters length:" + args.length);
		if (args.length >= 3) {
			ImapCopier imapCopier = new ImapCopier();
			imapCopier.setEndYear(args[2]);
			try {
				log.debug("opening conections");
				imapCopier.openSourceConnection(args[0].trim());
				imapCopier.openTargetConnection(args[1].trim());
				if (args.length > 3) {
					for (int i = 3; i < args.length; i++) {
						imapCopier.addFilteredFolder(args[i]);
					}
				}
				imapCopier.copy();
			} finally {
				imapCopier.close();
			}
		} else {
			String usage = "usage: imapCopy source target untilyear [excludedFolder]\n";
			usage += "source & target format: protocol://user[:password]@server[:port]\n";
			usage += "protocols: imap or imaps";
			System.out.println(usage);
		}
	}

	public void addFilteredFolder(String folderName) {
		log.debug("Adding '" + folderName + "' to filtered folders");
		filteredFolders.add(folderName);
	}
	
	private void setEndYear(String year) {
		endYear = Integer.parseInt(year);
	}

	/**
	 * Open a connection to the source Store from where the messages will be copied when <code>copy</code> method will
	 * be executed
	 * 
	 * @param storeType Type of Store (imap, aimaps, pop3, pop3s)
	 * @param host Server host name
	 * @param user User
	 * @param password Password
	 * @throws MessagingException
	 */
	public void openSourceConnection(String storeType, String host, String user, String password)
			throws MessagingException {
		this.sourceStore = this.openConnection(storeType, host, user, password);
	}

	/**
	 * Open a connection to the source Store from where the messages will be copied when <code>copy</code> method will
	 * be executed
	 * 
	 * @param url URL in the format </code>protocol://user[:password]@server[:port]</code>
	 * @throws MessagingException
	 */
	public void openSourceConnection(String url) throws MessagingException {
		this.sourceStore = this.openConnection(url);
	}

	/**
	 * Open a connection to the target Store where the messages will be copied when <code>copy</code> method will be
	 * executed
	 * 
	 * @param storeType Type of Store (imap, aimaps, pop3, pop3s)
	 * @param host Server host name
	 * @param user User
	 * @param password Password
	 * @throws MessagingException
	 */
	public void openTargetConnection(String storeType, String host, String user, String password)
			throws MessagingException {
		this.targetStore = this.openConnection(storeType, host, user, password);
	}

	/**
	 * Open a connection to the target Store where the messages will be copied when <code>copy</code> method will be
	 * executed
	 * 
	 * @param url URL in the format </code>protocol://user[:password]@server[:port]</code>
	 * @throws MessagingException
	 */
	public void openTargetConnection(String url) throws MessagingException {
		this.targetStore = this.openConnection(url);
	}
	
	/**
	 * Open a connection to the target Store where the messages will be copied when
	 * <code>copy</code> method will be executed
	 * 
	 * @param url URL in the format </code>protocol://user[:password]@server[:port]</code>
	 * @throws MessagingException
	 */
	public void openTargetForCheckConnection(String url) throws MessagingException {
		this.targetStoreForCheck = this.openConnection(url);
	}

	/**
	 * Opens a connection to a mail server
	 * 
	 * @param storeType Type of Store (imap, aimaps, pop3, pop3s)
	 * @param host Server host name
	 * @param user User
	 * @param password Password
	 * @return Mail Store for the connection
	 * @throws MessagingException
	 */
	private Store openConnection(String storeType, String host, String user, String password) throws MessagingException {
		log.debug("opening " + storeType + " conection to " + host);
		Properties props = new Properties();
		Session session = Session.getDefaultInstance(props);
		Store store = session.getStore(storeType);
		store.connect(host, user, password);
		log.debug(store.getURLName().toString());

		return store;
	}

	/**
	 * Opens a connection to a mail server
	 * 
	 * @param url URL in the format </code>protocol://user[:password]@server[:port]</code>
	 * @return Mail Store for the connection
	 * @throws MessagingException
	 */
	private Store openConnection(String url) throws MessagingException {
		URLName urlName = new URLName(url);
		log.debug("opening " + urlName.getProtocol() + " conection to " + urlName.getHost());
		Properties props = new Properties();
		Session session = Session.getDefaultInstance(props);
		Store store = session.getStore(urlName);
		store.connect();

		return store;
	}

	/**
	 * Copy the folders and messages defined with the methods <code>openSourceConnection</code> and
	 * <code>openTargetConnection</code>
	 * 
	 * @throws MessagingException
	 */
	public void copy() throws MessagingException {
		ImapCopyAplicationEvent evt = new ImapCopyAplicationEvent(ImapCopyAplicationEvent.START);
		for (ImapCopyListenerInterface listener : listeners) {
			listener.eventNotification(evt);
		}

		Folder defaultSourceFolder = this.sourceStore.getDefaultFolder();
		Folder defaultTargetFolder = this.targetStore.getDefaultFolder();
		copyFolderAndMessages(defaultSourceFolder, defaultTargetFolder, true);

		evt = new ImapCopyAplicationEvent(ImapCopyAplicationEvent.END);
		for (ImapCopyListenerInterface listener : listeners) {
			listener.eventNotification(evt);
		}
	}
	
	/**
	 * Check the target store for already copied messages.
	 * @throws MessagingException 
	 */
	public void checkAlreadyCopied() throws MessagingException {
		log.info("checking already copied messages");
		Folder defaultTargetFolder = this.targetStoreForCheck.getDefaultFolder();
		checkFolderAndMessages(defaultTargetFolder, true);
		log.info("checking completed. Now starting copying");
		this.targetStoreForCheck.close(); // close the store, we don't need it anymore
	}

	/**
	 * check folders recursively.
	 * @param targetFolder
	 * @param isDefaultFolder
	 * @throws MessagingException
	 */
	public void checkFolderAndMessages(Folder targetFolder, boolean isDefaultFolder) throws MessagingException {
		Folder[] childFolders = targetFolder.list(); // call list() before open() !
		if (!isDefaultFolder) {
			log.info("checking folder: " + targetFolder.getFullName());
			targetFolder.open(Folder.READ_ONLY);
			Message[] messages = targetFolder.getMessages();
			if (messages.length > 0) {
				for (Message message : messages) {
					String uniqueId = buildUniqueId(targetFolder, message);
					alreadyCopied.add(uniqueId);
				}
			}
		}
		for (Folder child : childFolders) {
			checkFolderAndMessages(child, false);
		}

	}
	
	private String buildUniqueId(Folder targetFolder, Message message)
			throws MessagingException {
		String[] mesgIds = message.getHeader("Message-ID");
		String msgId = mesgIds == null ? message.getSubject() : mesgIds[0];
		Date sentDate = message.getSentDate();
		long time = sentDate == null ? Long.MIN_VALUE : sentDate.getTime();
		return targetFolder.getFullName() + time + msgId;
	}

	/**
	 * Copy recusively the soruce folder and his contents to the target folder
	 * 
	 * @param sourceFolder Source Folder
	 * @param targetFolder Target Folder
	 * @param isDefaultFolder Flag if the folder are the defualt folder of thre store
	 * @throws MessagingException
	 */
	private void copyFolderAndMessages(Folder sourceFolder, Folder targetFolder, boolean isDefaultFolder)
			throws MessagingException {

		if (sourceFolder.exists() && !filteredFolders.contains(sourceFolder.getFullName())) {
			if (!isDefaultFolder) {
				openfolderIfNeeded(sourceFolder, Folder.READ_ONLY);
				openfolderIfNeeded(targetFolder, Folder.READ_ONLY);

				Message[] notCopiedMessages = filterMessages(sourceFolder, getNotCopiedMessages(sourceFolder, targetFolder));

				log.debug("Copying " + notCopiedMessages.length + " messages from " + sourceFolder.getFullName()
						+ " Folder");
				if (notCopiedMessages.length > 0) {
					openfolderIfNeeded(targetFolder, Folder.READ_WRITE);
					try {
						targetFolder.appendMessages(notCopiedMessages);
					} catch (MessagingException e) {
						log.error("Error copying messages from " + sourceFolder.getFullName() + " Folder", e);
						copyMessagesOneByOne(targetFolder, notCopiedMessages);
					}
					closeFolderIfNeeded(targetFolder);
				}
				closeFolderIfNeeded(sourceFolder);
			}

			Folder[] sourceFolders = sourceFolder.list();
			logFoldersList("Source Folders", sourceFolders);
			for (Folder sourceSubFolder : sourceFolders) {
				Folder targetSubFolder = targetFolder.getFolder(sourceSubFolder.getName());
				if (!targetSubFolder.exists()) {
					log.debug("Creating target Folder: " + targetSubFolder.getFullName());
					targetSubFolder.create(sourceSubFolder.getType());
				}
				notifyToListeners(targetSubFolder);
				copyFolderAndMessages(sourceSubFolder, targetSubFolder, false);
			}
		} else {
			log.info("Skipping folder " + sourceFolder.getFullName());
		}
	}

	private Message[] getNotCopiedMessages(Folder sourceFolder, Folder targetFolder) throws MessagingException {
		log.info("Looking for non synced messages from folder " + sourceFolder.getFullName());
		List<Message> sourceMessages = Arrays.asList(sourceFolder.getMessages());
		List<Message> res = ListUtils.select(sourceMessages, new MessageFilterPredicate(targetFolder.getMessages()));

		return res.toArray(new Message[0]);
	}
	
	/**
	 * filter messages by sent-date.
	 * @param input
	 * @return
	 * @throws MessagingException
	 */
	private Message[] filterMessages(Folder folder, Message[] input) throws MessagingException {
		List<Message> result = new ArrayList<Message>();
		for (Message message : input) {
			
			Date sentDate = message.getSentDate();
			if (sentDate == null || (sentDate.getYear() +1900) > endYear) continue;
			result.add(message);
		}
		return result.toArray(new Message[0]);
	}

	/**
	 * Copy the list of messages one by one to the target Folder
	 * 
	 * @param targetFolder Folder to store messages
	 * @param messages List of messages
	 */
	private void copyMessagesOneByOne(Folder targetFolder, Message[] messages) {
		int counter = 0;
		for (Message message : messages) {
			counter++;
			Message[] aux = new Message[1];
			aux[0] = message;
			try {
				openfolderIfNeeded(targetFolder, Folder.READ_WRITE);
				targetFolder.appendMessages(aux);
			} catch (MessagingException e) {
				log.error("Error copying 1 message to " + targetFolder.getFullName() + " Folder", e);
			}
			if (counter % 100 == 0) {
				log.debug("Copied " + counter + " messages to " + targetFolder.getFullName());
			}
		}
	}

	private void openfolderIfNeeded(Folder folder, int mode) throws MessagingException {
		if (!folder.isOpen() || folder.getMode() != mode) {
			closeFolderIfNeeded(folder);
			folder.open(mode);
		}
	}

	private void closeFolderIfNeeded(Folder folder) {
		if (folder.isOpen()) {
			try {
				folder.close(false);
			} catch (MessagingException e) {
				log.warn("Problems closing folder", e);
			}
		}
	}

	private void notifyToListeners(Folder folder) {
		ImapCopyFolderEvent evt = new ImapCopyFolderEvent(folder.getFullName());
		for (ImapCopyListenerInterface listener : listeners) {
			listener.eventNotification(evt);
		}
	}

	public void addImapCopyListener(ImapCopyListenerInterface listener) {
		this.listeners.add(listener);
	}

	/**
	 * Closes the open resources
	 * 
	 * @throws MessagingException
	 */
	public void close() throws MessagingException {
		if (this.sourceStore != null)
			this.sourceStore.close();

		if (this.targetStore != null)
			this.targetStore.close();
	}

	private void logFoldersList(String message, Folder[] folders) {
		String txt = message + ": ";
		for (Folder folder : folders) {
			txt += folder.getFullName() + "(" + getFolderMessageCount(folder) + "), ";
		}
		if (folders.length > 0) {
			log.debug(txt);
		}
	}

	private int getFolderMessageCount(Folder folder) {
		int res = -1;
		try {
			res = folder.getMessageCount();
		} catch (MessagingException e) {
			log.warn("Problems getting folder size - " + folder.getFullName());
		}

		return res;
	}

	public void run() {
		try {
			this.copy();
		} catch (MessagingException e) {
			e.printStackTrace();
		} finally {
			try {
				this.close();
			} catch (MessagingException e) {
				e.printStackTrace();
			}
		}
	}

	class MessageFilterPredicate implements Predicate<Message> {
		Set<String> messagesId = new HashSet<String>();

		public MessageFilterPredicate(Message[] filterMessages) throws MessagingException {
			for (Message message : filterMessages) {
				String[] msgHeader = message.getHeader("Message-ID");
				if (msgHeader != null) {
					messagesId.add(msgHeader[0]);
				}
				else {
					try {
						log.warn("Message has no Message-ID header! " + message.getContent().toString());
					} catch (IOException ioe) {
						log.error("Error while logging", ioe);
					}
				}
			}
		}

		public boolean evaluate(Message message) {
			boolean res = true;
			try {
				
				String[] msgHeader = message.getHeader("Message-ID");
				res = messagesId.isEmpty() || (msgHeader != null && !messagesId.contains(msgHeader[0]));
			} catch (MessagingException e) {}

			return res;
		}
	}
}