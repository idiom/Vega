/*******************************************************************************
 * Copyright (c) 2011 Subgraph.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Subgraph - initial API and implementation
 ******************************************************************************/
package com.subgraph.vega.internal.crawler;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.util.EntityUtils;

public class HttpResponseProcessor implements Runnable {
	private final Logger logger = Logger.getLogger("crawler");
	private final WebCrawler crawler;
	private final BlockingQueue<CrawlerTask> crawlerRequestQueue;
	private final BlockingQueue<CrawlerTask> crawlerResponseQueue;
	private final CountDownLatch latch;
	private final TaskCounter counter;
	private final AtomicInteger outstandingTasks;
	private volatile boolean stop;
	private final Object requestLock = new Object();
	private volatile HttpUriRequest activeRequest = null;

	HttpResponseProcessor(WebCrawler crawler, BlockingQueue<CrawlerTask> requestQueue, BlockingQueue<CrawlerTask> responseQueue, CountDownLatch latch, TaskCounter counter, AtomicInteger outstandingTasks) {
		this.crawler = crawler;
		this.crawlerRequestQueue = requestQueue;
		this.crawlerResponseQueue = responseQueue;
		this.latch = latch;
		this.counter = counter;
		this.outstandingTasks = outstandingTasks;
	}

	@Override
	public void run() {
		try {
			runLoop();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			latch.countDown();
		}
	}

	void stop() {
		stop = true;
		if(!crawlerResponseQueue.offer(CrawlerTask.createExitTask())) {
			logger.warning("Failed to add STOP sentinel to crawler response queue");
		}
		synchronized(requestLock) {
			if(activeRequest != null)
				activeRequest.abort();
		}
	}

	private void runLoop() throws InterruptedException {
		while(!stop) {
			CrawlerTask task = crawlerResponseQueue.take();
			if(task.isExitTask()) {
				crawlerRequestQueue.add(CrawlerTask.createExitTask());
				crawlerResponseQueue.add(task);
				return;
			}
			HttpUriRequest req = task.getRequest();
			activeRequest = req;
			try {
				if(task.getResponse() != null) {
					task.getResponseProcessor().processResponse(crawler, req, task.getResponse(), task.getArgument());
				}
			} catch (Exception e) {
				logger.log(Level.WARNING, "Unexpected exception processing crawler request: "+ req.getURI(), e);
			} finally {
				synchronized (requestLock) {
					activeRequest = null;
				}
				final HttpEntity entity = (task.getResponse() == null) ? (null) : task.getResponse().getRawResponse().getEntity();
				if(entity != null)
					try {
						EntityUtils.consume(entity);
					} catch (IOException e) {
						logger.log(Level.WARNING, "I/O exception consuming request entity content for "+ req.getURI() + " : "+ e.getMessage());
					}
			}

			synchronized(counter) {
				counter.addCompletedTask();
				crawler.updateProgress();
			}
			if(task.causedException()) {
				crawler.notifyException(req, task.getException());
			}

			if(outstandingTasks.decrementAndGet() <= 0) {
				crawlerRequestQueue.add(CrawlerTask.createExitTask());
				crawlerResponseQueue.add(CrawlerTask.createExitTask());
				return;
			}
		}
	}
}
