package nhb.mario.test;

import java.text.DecimalFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.mario.entity.impl.BaseMessageHandler;
import com.mario.entity.message.Message;
import com.nhb.common.async.Callback;
import com.nhb.common.data.PuDataType;
import com.nhb.common.data.PuElement;
import com.nhb.common.data.PuValue;
import com.nhb.common.utils.TimeWatcher;
import com.nhb.messaging.zmq.ZMQFuture;
import com.nhb.messaging.zmq.producer.ZMQRPCProducer;

public class EchoServer extends BaseMessageHandler {

	@Override
	public PuElement handle(Message message) {
		return message.getData();
	}

	@Override
	public void onServerReady() {
		final ZMQRPCProducer producer = this.getApi().getProducer("test_producer");

		new Thread(() -> {
			try {
				startTesting(producer);
			} catch (InterruptedException | ExecutionException e) {
				getLogger().error("Error while testing", e);
			}
		}).start();
	}

	private void startTesting(ZMQRPCProducer producer) throws InterruptedException, ExecutionException {
		final int messageSize = 512;

		int count = 10;
		while (count-- > 0) {
			try {
				producer.publish(PuValue.fromObject("ping")).get(100, TimeUnit.MILLISECONDS);
				break;
			} catch (TimeoutException e) {
				// getLogger().error("timeout exception: ", e);
			}

			if (count == 0) {
				throw new RuntimeException("Cannot establish bi-direction communication to consumer");
			}
		}

		int numMessages = (int) 1e6;
		PuValue data = new PuValue(new byte[messageSize - 3 /* for msgpack meta */], PuDataType.RAW);

		getLogger().debug("Start sending....");
		// reset receiveCouter
		AtomicInteger sentCounter = new AtomicInteger(0);
		CountDownLatch doneSignal = new CountDownLatch(numMessages);
		Thread monitor = new Thread(() -> {
			DecimalFormat dfP = new DecimalFormat("0.##%");
			DecimalFormat df = new DecimalFormat("###,###.##");
			while (!Thread.currentThread().isInterrupted()) {
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					return;
				}
				long remainingCount = doneSignal.getCount();
				int sentCount = sentCounter.get();
				getLogger().debug("Total: {}, sent: {}, remaining: {} (in producer: {}) --> done: {}" //
				, df.format(numMessages) // total
				, dfP.format(Double.valueOf(sentCount) / numMessages) // sent percentage
				, df.format(remainingCount) // remaining
				, df.format(producer.remaining()) // remaining future in producer
				, dfP.format(Double.valueOf(numMessages - remainingCount) / numMessages) // done percentage
				);
			}
		}, "monitor");
		monitor.start();

		TimeWatcher timeWatcher = new TimeWatcher();
		timeWatcher.reset();
		for (int i = 0; i < numMessages; i++) {
			final ZMQFuture future = producer.publish(data);
			future.setCallback(new Callback<PuElement>() {

				@Override
				public void apply(PuElement result) {
					if (result == null) {
						getLogger().error("Error: ", future.getFailedCause());
					}
					doneSignal.countDown();
				}
			});
			sentCounter.incrementAndGet();
		}
		doneSignal.await();
		double totalTimeSeconds = timeWatcher.endLapSeconds();

		Thread.sleep(500);
		monitor.interrupt();

		double avgMessageSize = data.toBytes().length;
		double totalSentBytes = avgMessageSize * numMessages;
		double totalIOBytes = totalSentBytes * 2;

		DecimalFormat df = new DecimalFormat("###,###.##");

		getLogger().info("************** STATISTIC **************");
		getLogger().info("Num msgs: {}", df.format(numMessages));
		getLogger().info("Elapsed: {} seconds", df.format(totalTimeSeconds));
		getLogger().info("Avg msg size: {} bytes", df.format(avgMessageSize));
		getLogger().info("Msg rate: {} msg/s", df.format(Double.valueOf(numMessages) / totalTimeSeconds));
		getLogger().info("Total sent bytes: {} bytes == {} KB == {} MB", df.format(totalSentBytes),
				df.format(totalSentBytes / 1024), df.format(totalSentBytes / 1024 / 1024));

		getLogger().info("Sending throughput: {} bytes/s == {} KB/s == {} MB/s",
				df.format(totalSentBytes / totalTimeSeconds), df.format(totalSentBytes / 1024 / totalTimeSeconds),
				df.format(totalSentBytes / 1024 / 1024 / totalTimeSeconds));
		getLogger().info("Total I/O throughput: {} bytes/s == {} KB/s == {} MB/s",
				df.format(totalIOBytes / totalTimeSeconds), df.format(totalIOBytes / 1024 / totalTimeSeconds),
				df.format(totalIOBytes / 1024 / 1024 / totalTimeSeconds));

		getLogger().info("**************** DONE ****************");
	}
}
