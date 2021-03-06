package com.outbrain.aletheia.datum.consumption;

import com.outbrain.aletheia.breadcrumbs.BreadcrumbDispatcher;
import com.outbrain.aletheia.datum.envelope.avro.DatumEnvelope;
import com.outbrain.aletheia.datum.serialization.DatumSerDe;
import com.outbrain.aletheia.datum.serialization.DatumTypeVersion;
import com.outbrain.aletheia.datum.serialization.SerializedDatum;
import com.outbrain.aletheia.metrics.common.Counter;
import com.outbrain.aletheia.metrics.common.Histogram;
import com.outbrain.aletheia.metrics.common.MetricsFactory;
import com.outbrain.aletheia.metrics.common.TimeWindowAverager;
import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatumEnvelopeOpener<TDomainClass> {

  private static final Logger logger = LoggerFactory.getLogger(DatumEnvelopeOpener.class);

  private final BreadcrumbDispatcher<TDomainClass> datumAuditor;
  private final DatumSerDe<TDomainClass> datumSerDe;

  private final TimeWindowAverager logicalDelayAverager;
  private final Histogram logicalTimestampDelayHistogram;

  private final Counter futureLogicalMessagesCount;

  public DatumEnvelopeOpener(final BreadcrumbDispatcher<TDomainClass> datumAuditor,
                             final DatumSerDe<TDomainClass> datumSerDe,
                             final MetricsFactory metricFactory) {

    this.datumAuditor = datumAuditor;
    this.datumSerDe = datumSerDe;

    logicalDelayAverager = new TimeWindowAverager(60.0, 15, -1.0);

    futureLogicalMessagesCount = metricFactory.createCounter("Timestamp.Logical", "FromTheFuture");
    logicalTimestampDelayHistogram = metricFactory.createHistogram("Timestamp.Logical",
                                                                   "DelayHistogramInSeconds",
                                                                   true);
    metricFactory.createGauge("Timestamp.Logical", "DelayAverageInSeconds", logicalDelayAverager);
  }

  private void updateLagMetrics(final DatumEnvelope envelope) {
    final DateTime now = DateTime.now();
    try {
      final long logicalSecondsBehind =
              new Interval(new DateTime(envelope.getLogicalTimestamp()), now).toDuration().getStandardSeconds();
      logicalDelayAverager.addSample((int) logicalSecondsBehind);
      logicalTimestampDelayHistogram.update(logicalSecondsBehind);
    } catch (final IllegalArgumentException e) {
      logger.warn(String.format("A message with a future logical timestamp has arrived from %s. " +
                                "Arriving timestamp is: %d, now reference is %d, delta is: %d, skipping lag metrics update.",
                                envelope.getSourceHost(),
                                envelope.getLogicalTimestamp(),
                                now.getMillis(),
                                envelope.getLogicalTimestamp() - now.getMillis()));
      futureLogicalMessagesCount.inc();
    } catch (final Exception e) {
      logger.error("Error while updating lag metric", e);
      futureLogicalMessagesCount.inc();
    }
  }

  public TDomainClass open(final DatumEnvelope datumEnvelope) {

    updateLagMetrics(datumEnvelope);

    final TDomainClass datum =
            datumSerDe.deserializeDatum(new SerializedDatum(datumEnvelope.getDatumBytes(),
                                                            new DatumTypeVersion(
                                                                    datumEnvelope.getDatumTypeId().toString(),
                                                                    datumEnvelope.getDatumSchemaVersion())));
    datumAuditor.report(datum);

    return datum;
  }
}

