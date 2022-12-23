/*
 * Copyright 2016 KairosDB Authors
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.kairosdb.core.telnet;

import com.google.common.collect.ImmutableSortedMap;
import com.google.inject.Inject;
import org.jboss.netty.channel.Channel;
import org.kairosdb.core.DataPoint;
import org.kairosdb.core.datapoints.DoubleDataPointFactory;
import org.kairosdb.core.datapoints.LongDataPointFactory;
import org.kairosdb.core.exception.DatastoreException;
import org.kairosdb.eventbus.FilterEventBus;
import org.kairosdb.eventbus.Publisher;
import org.kairosdb.events.DataPointEvent;
import org.kairosdb.metrics4j.MetricSourceManager;
import org.kairosdb.util.Tags;
import org.kairosdb.util.Util;
import org.kairosdb.util.ValidationException;
import org.kairosdb.util.Validator;

import java.util.List;

import static org.kairosdb.util.Preconditions.requireNonNullOrEmpty;

public class PutMillisecondCommand implements TelnetCommand
{
	private static final TelnetStats stats = MetricSourceManager.getSource(TelnetStats.class);

	private LongDataPointFactory m_longFactory;
	private DoubleDataPointFactory m_doubleFactory;
	private final Publisher<DataPointEvent> m_publisher;

	@Inject
	public PutMillisecondCommand(FilterEventBus eventBus,
			LongDataPointFactory longFactory, DoubleDataPointFactory doubleFactory)
	{
		m_longFactory = longFactory;
		m_doubleFactory = doubleFactory;

		m_publisher = eventBus.createPublisher(DataPointEvent.class);
	}

	@Override
	public void execute(Channel chan, List<String> command) throws DatastoreException, ValidationException
	{
		long timestamp = Util.parseLong(command.get(2));
		execute(command, timestamp);
	}

	protected DataPoint createDataPoint(long timestamp, String value) throws ValidationException
	{
		DataPoint dp;
		try
		{
			if (value.contains("."))
				dp = m_doubleFactory.createDataPoint(timestamp, Double.parseDouble(value));
			else
				dp = m_longFactory.createDataPoint(timestamp, Util.parseLong(value));
		}
		catch (NumberFormatException e)
		{
			throw new ValidationException(e.getMessage());
		}

		return dp;
	}

	protected void execute(List<String> command, long timestamp) throws ValidationException, DatastoreException
	{
		Validator.validateNotNullOrEmpty("metricName", command.get(1));

		String metricName = command.get(1);
		int ttl = 0;

		DataPoint dp = createDataPoint(timestamp, command.get(3));

		ImmutableSortedMap.Builder<String, String> tags = Tags.create();

		int tagCount = 0;
		for (int i = 4; i < command.size(); i++)
		{
			String[] tag = command.get(i).split("=");
			validateTag(tagCount, tag);

			if ("kairos_opt.ttl".equals(tag[0]))
			{
				try
				{
					ttl = Integer.parseInt(tag[1]);
				}
				catch (NumberFormatException nfe)
				{
					throw new ValidationException("tag[kairos_opt.ttl] must be a number");
				}

			}
			else
			{
				tags.put(tag[0], tag[1]);
				tagCount++;
			}
		}

		if (tagCount == 0)
			tags.put("add", "tag");

		stats.request(getCommand()).put(1);
		m_publisher.post(new DataPointEvent(metricName, tags.build(), dp, ttl));
	}

	private void validateTag(int tagCount, String[] tag) throws ValidationException
	{
		if (tag.length < 2)
			throw new ValidationException(String.format("tag[%d] must be in the format 'name=value'.", tagCount));

		Validator.validateNotNullOrEmpty(String.format("tag[%d].name", tagCount), tag[0]);

		Validator.validateNotNullOrEmpty(String.format("tag[%d].value", tagCount), tag[1]);
	}

	@Override
	public String getCommand()
	{
		return ("putm");
	}

}
