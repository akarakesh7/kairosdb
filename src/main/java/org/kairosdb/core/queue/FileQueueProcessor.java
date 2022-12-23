package org.kairosdb.core.queue;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableSortedMap;
import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.kairosdb.core.DataPointSet;
import org.kairosdb.core.datapoints.LongDataPointFactory;
import org.kairosdb.core.datapoints.LongDataPointFactoryImpl;
import org.kairosdb.core.exception.DatastoreException;
import org.kairosdb.events.DataPointEvent;
import org.kairosdb.bigqueue.IBigArray;
import org.kairosdb.metrics4j.MetricSourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 Created by bhawkins on 12/14/16.
 */
public class FileQueueProcessor extends QueueProcessor
{
	public static final Logger logger = LoggerFactory.getLogger(FileQueueProcessor.class);
	public static final QueueStats stats = MetricSourceManager.getSource(QueueStats.class);
	public static final String SECONDS_TILL_CHECKPOINT = "kairosdb.queue_processor.seconds_till_checkpoint";

	private final Object m_lock = new Object();
	private final IBigArray m_bigArray;
	private final CircularFifoQueue<IndexedEvent> m_memoryQueue;
	private final DataPointEventSerializer m_eventSerializer;
	private final List<DataPointEvent> m_internalMetrics = new ArrayList<>();
	private Stopwatch m_stopwatch = Stopwatch.createStarted();
	private CompletionCallBack m_lastCallback = new CompletionCallBack();
	private final int m_secondsTillCheckpoint;
	private volatile boolean m_shuttingDown;

	private long m_nextIndex = -1L;


	@Inject
	public FileQueueProcessor(
			DataPointEventSerializer eventSerializer,
			IBigArray bigArray,
			@Named(QUEUE_PROCESSOR) ExecutorService executor,
			@Named(BATCH_SIZE) int batchSize,
			@Named(MEMORY_QUEUE_SIZE) int memoryQueueSize,
			@Named(SECONDS_TILL_CHECKPOINT) int secondsTillCheckpoint,
			@Named(MINIMUM_BATCH_SIZE) int minimumBatchSize,
			@Named(MINIMUM_BATCH_WAIT) int minBatchWait)
	{
		super(executor, batchSize, minimumBatchSize, minBatchWait);
		m_bigArray = bigArray;
		m_memoryQueue = new CircularFifoQueue<>(memoryQueueSize);
		m_eventSerializer = eventSerializer;
		m_nextIndex = m_bigArray.getTailIndex();
		m_secondsTillCheckpoint = secondsTillCheckpoint;
		m_shuttingDown = false;

		MetricSourceManager.addSource(QueueStats.class.getName(),
				"memoryQueueSize", null, "Amount of data in the memory queue", () -> m_memoryQueue.size());
		MetricSourceManager.addSource(QueueStats.class.getName(),
				"fileQueueSize", null, "Amount of data in the file queue", () -> m_bigArray.getHeadIndex() - m_nextIndex);
	}


	@Override
	public void shutdown()
	{
		//todo: would like to drain the queue before shutting down.
		m_shuttingDown = true;

		m_bigArray.flush();
		try
		{
			m_bigArray.close();
		}
		catch (IOException e)
		{
			logger.warn("Error while shutting down bigqueue", e);
		}

		super.shutdown();
	}

	private long incrementIndex(long index)
	{
		if (index == Long.MAX_VALUE)
			return 0;

		return index + 1;
	}

	private void waitForEvent()
	{
		boolean waited = false;

		synchronized (m_lock)
		{
			if (m_memoryQueue.isEmpty())
			{
				try
				{
					waited = true;
					//System.out.println("Waiting for event");
					m_lock.wait();
				}
				catch (InterruptedException e)
				{
					logger.info("Queue processor sleep interrupted");
				}
			}
		}

		if (waited)
		{
			try
			{
				//Adding sleep after waiting for data helps ensure we batch incoming
				//data instead of getting the first one right off and sending it alone
				Thread.sleep(50);
			}
			catch (InterruptedException e)
			{
				e.printStackTrace();
			}
		}

	}


	public void put(DataPointEvent dataPointEvent) throws DatastoreException
	{
		if (m_shuttingDown)
		{
			throw new DatastoreException("File Queue shutting down");
		}

		byte[] eventBytes = m_eventSerializer.serializeEvent(dataPointEvent);

		try
		{
			synchronized (m_lock)
			{
				long index = -1L;
				//Add data to bigArray first
				index = m_bigArray.append(eventBytes);
				//Then stick it into the in memory queue
				m_memoryQueue.add(new IndexedEvent(dataPointEvent, index));

				//Notify the reader thread if it is waiting for data
				m_lock.notify();
			}
		}
		catch (IOException ioe)
		{
			throw new DatastoreException("Failure to write data to bigqueue", ioe);
		}
	}

	@Override
	protected int getAvailableDataPointEvents()
	{
		return m_memoryQueue.size();
	}

	@Override
	protected List<DataPointEvent> get(int batchSize)
	{
		if (m_memoryQueue.isEmpty())
			waitForEvent();

		List<DataPointEvent> ret = new ArrayList<>();
		long returnIndex = 0L;

		synchronized (m_lock)
		{
			ret.addAll(m_internalMetrics);
			m_internalMetrics.clear();

			for (int i = ret.size(); i < batchSize; i++)
			{
				//System.out.println(m_nextIndex);
				IndexedEvent event = m_memoryQueue.peek();
				if (event != null)
				{
					if (event.m_index == m_nextIndex)
					{
						m_memoryQueue.remove();
					}
					else
					{
						if (m_nextIndex != m_bigArray.getHeadIndex())
						{
							try
							{
								DataPointEvent dataPointEvent = m_eventSerializer.deserializeEvent(m_bigArray.get(m_nextIndex));
								event = new IndexedEvent(dataPointEvent, m_nextIndex);
								stats.readFromFile().put(1);
							}
							catch (IOException ioe)
							{
								logger.error("Unable to read from bigqueue", ioe);
								break;
							}
						}
					}

					returnIndex = m_nextIndex;
					m_nextIndex = incrementIndex(m_nextIndex);
					if (event.m_dataPointEvent != null)
					{
						ret.add(event.m_dataPointEvent);
					}
				}
				else
					break; //exhausted queue

			}
		}

		//System.out.println(ret.size());
		stats.processCount("file").put(ret.size());

		m_lastCallback.increment();
		m_lastCallback.setCompletionIndex(returnIndex);
		return ret;
	}

	@Override
	protected EventCompletionCallBack getCompletionCallBack()
	{
		CompletionCallBack callbackToReturn = m_lastCallback;

		if (m_stopwatch.elapsed(TimeUnit.SECONDS) > m_secondsTillCheckpoint)
		{
			//System.out.println("Checkpoint");
			callbackToReturn.setFinalized();
			m_lastCallback = new CompletionCallBack();
			callbackToReturn.setChildCallBack(m_lastCallback);
			m_stopwatch.reset();
			m_stopwatch.start();
		}

		return callbackToReturn;
	}


	/**
	 The purpose of this class is to track all batches sent up to a certain point
	 and once they are finished (via a call to complete) this will move the
	 tail of the big array.
	 */
	private class CompletionCallBack implements EventCompletionCallBack
	{
		private long m_completionIndex;
		private final AtomicInteger m_counter;
		private volatile boolean m_finalized;
		private CompletionCallBack m_childCallBack;

		private CompletionCallBack()
		{
			m_counter = new AtomicInteger(0);
			m_finalized = false;
		}

		public void setChildCallBack(CompletionCallBack childCallBack)
		{
			m_childCallBack = childCallBack;
			m_childCallBack.increment();
		}

		public void setCompletionIndex(long completionIndex)
		{
			m_completionIndex = completionIndex;
		}

		public void increment()
		{
			m_counter.incrementAndGet();
		}

		/**
		 The finalize method gets called always before the last call to complete
		 No need for locking
		 */
		public void setFinalized()
		{
			m_finalized = true;
		}

		@Override
		public void complete()
		{
			if (m_counter.decrementAndGet() == 0 && m_finalized)
			{
				m_childCallBack.complete();
				//Checkpoint big queue
				try
				{
					m_bigArray.removeBeforeIndex(m_completionIndex);
				}
				catch (IOException e)
				{
					logger.warn("Unable to cleanup bigqueue", e);
				}
			}
		}
	}

	/**
	 Holds a DataPointEvent and the index it is at in the BigArray.
	 Basically to keep the in memory circular queue and BigArray in sync.
	 */
	private static class IndexedEvent
	{
		public final DataPointEvent m_dataPointEvent;
		public final long m_index;

		public IndexedEvent(DataPointEvent dataPointEvent, long index)
		{
			m_dataPointEvent = dataPointEvent;
			m_index = index;
		}
	}

}
