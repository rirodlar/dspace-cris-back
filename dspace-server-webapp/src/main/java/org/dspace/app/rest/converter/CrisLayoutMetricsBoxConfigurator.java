/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.converter;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.dspace.app.rest.model.CrisLayoutBoxConfigurationRest;
import org.dspace.app.rest.model.CrisLayoutMetricsConfigurationRest;
import org.dspace.content.CrisLayoutMetric2BoxPriorityComparator;
import org.dspace.core.Context;
import org.dspace.layout.CrisLayoutBox;
import org.dspace.layout.CrisLayoutBoxTypes;
import org.dspace.layout.CrisLayoutMetric2Box;
import org.dspace.layout.service.CrisLayoutMetric2BoxService;
import org.dspace.metrics.CrisItemMetricsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * This is the configurator for metrics layout box
 * 
 * @author Alessandro Martelli (alessandro.martelli at 4science.it)
 *
 */
@Component
public class CrisLayoutMetricsBoxConfigurator implements CrisLayoutBoxConfigurator {

    @Autowired
    private CrisItemMetricsService crisItemMetricsService;

    @Autowired
    private CrisLayoutMetric2BoxService crisLayoutMetric2BoxService;

    @Override
    public boolean support(CrisLayoutBox box) {
        return StringUtils.equals(box.getType(), CrisLayoutBoxTypes.METRICS.name());
    }

    @Override
    public CrisLayoutBoxConfigurationRest getConfiguration(CrisLayoutBox box) {
        CrisLayoutMetricsConfigurationRest rest = new CrisLayoutMetricsConfigurationRest();
        rest.setMaxColumns(box.getMaxColumns());
        List<CrisLayoutMetric2Box> layoutMetrics = box.getMetric2box();
        Collections.sort(layoutMetrics, new CrisLayoutMetric2BoxPriorityComparator());
        rest.setMetrics(metricList(layoutMetrics));
        return rest;
    }

    private List<String> metricList(final List<CrisLayoutMetric2Box> layoutMetrics) {
        final List<String> result = new LinkedList<>();
        layoutMetrics.forEach(lm -> {
            final String type = lm.getType();
            result.add(type);
            crisItemMetricsService
                .embeddableFallback(type)
                .ifPresent(result::add);
        });
        return result;
    }

    @Override
    public void configure(Context context, CrisLayoutBox box, CrisLayoutBoxConfigurationRest rest) {
        if (!(rest instanceof CrisLayoutMetricsConfigurationRest)) {
            throw new IllegalArgumentException("Invalid METRICS configuration provided");
        }

        CrisLayoutMetricsConfigurationRest metricsConfiguration = ((CrisLayoutMetricsConfigurationRest) rest);
        box.setMaxColumns(metricsConfiguration.getMaxColumns());
        crisLayoutMetric2BoxService.addMetrics(context, box, metricsConfiguration.getMetrics());

    }

}
