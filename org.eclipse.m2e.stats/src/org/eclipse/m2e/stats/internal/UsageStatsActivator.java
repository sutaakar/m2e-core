/*******************************************************************************
 * Copyright (c) 2013 Igor Fedorenko
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *      Igor Fedorenko - initial API and implementation
 *******************************************************************************/

package org.eclipse.m2e.stats.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.Future;

import org.apache.maven.wagon.proxy.ProxyInfo;
import org.apache.maven.wagon.proxy.ProxyUtils;
import org.apache.maven.wagon.repository.Repository;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.internal.MavenPluginActivator;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.service.prefs.BackingStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.ProxyServer;
import com.ning.http.client.Response;
import com.ning.http.client.SimpleAsyncHttpClient;
import com.ning.http.client.SimpleAsyncHttpClient.ErrorDocumentBehaviour;


/**
 * Weekly usage statistics reporter.
 * <p>
 * The following information is reported
 * <ul>
 * <li>workspace id, generated as UUID.randomUUID and persisted in bundle preferences</li>
 * <li>number of opened Maven workspace projects</li>
 * <li>m2e fully qualified version</li>
 * <li>equinox fully qualified version, as proxy for eclipse version</li>
 * <li>java version</li>
 * </ul>
 * <p>
 * Usages is reported weekly and report is delayed {@link #REPORT_MINIMAL_DELAY} milliseconds from bundle activation.
 * <p>
 * To disable usage reporting, this bundle needs to be stopped or remove from eclipse installation.
 * 
 * @see UsageStatsStartupHook
 */
@SuppressWarnings("restriction")
public class UsageStatsActivator implements BundleActivator {

  private final Logger log = LoggerFactory.getLogger(getClass());

  private static final String BUNDLE_ID = "org.eclipse.m2e.stats";

  private static final String PREF_INSTANCEID = "eclipse.m2.stats.instanceId";

  private static final String PREF_NEXTREPORT = "eclipse.m2.stats.nextReport";

  private static final long REPORT_MINIMAL_DELAY = 10 * 60 * 1000L; // 10 minutes

  private static final long REPORT_PERIOD = 7 * 86400L; // 7 days

  private static final String REPORT_URL = "http://localhost:8087/stats";

  private Timer timer;

  private IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(BUNDLE_ID);

  private class UsageStatsReporter extends TimerTask {
    @Override
    public void run() {
      // update next report time regardless if we report or not
      prefs.putLong(PREF_NEXTREPORT, System.currentTimeMillis() + REPORT_PERIOD);
      flushPreferences();

      final int projectCount = MavenPlugin.getMavenProjectRegistry().getProjects().length;
      if(projectCount > 0) {
        String instanceId = prefs.get(PREF_INSTANCEID, null);
        if(instanceId == null) {
          instanceId = UUID.randomUUID().toString();
          prefs.put(PREF_INSTANCEID, instanceId);
          flushPreferences();
        }

        String m2eVersion = MavenPluginActivator.getQualifiedVersion();

        String osgiVersion = Platform
            .getBundle("org.eclipse.osgi").getHeaders().get(org.osgi.framework.Constants.BUNDLE_VERSION); //$NON-NLS-1$

        String javaVersion = System.getProperty("java.version", "unknown"); //$NON-NLS-1$ $NON-NLS-1$

        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("uid", instanceId);
        params.put("pc", Integer.toString(projectCount));
        params.put("m", m2eVersion);
        params.put("e", osgiVersion);
        params.put("j", javaVersion);

        StringBuilder sb = new StringBuilder();
        sb.append("Reporting usage stats url=").append(REPORT_URL);
        for(Map.Entry<String, String> param : params.entrySet()) {
          sb.append(' ').append(param.getKey()).append('=').append(param.getValue());
        }
        log.info(sb.toString());

        post(REPORT_URL, params);
      }
    }

    void flushPreferences() {
      try {
        prefs.flush();
      } catch(BackingStoreException e) {
        log.debug("Could not update preferences", e);
      }
    }

    private void post(String url, Map<String, String> params) {
      SimpleAsyncHttpClient.Builder sahcBuilder = new SimpleAsyncHttpClient.Builder();
      sahcBuilder.setUserAgent(MavenPluginActivator.getUserAgent());
      sahcBuilder.setConnectionTimeoutInMs(15 * 1000);
      sahcBuilder.setRequestTimeoutInMs(60 * 1000);
      sahcBuilder.setCompressionEnabled(true);
      sahcBuilder.setFollowRedirects(true);
      sahcBuilder.setErrorDocumentBehaviour(ErrorDocumentBehaviour.OMIT);

      ProxyInfo proxyInfo = null;
      try {
        proxyInfo = MavenPlugin.getMaven().getProxyInfo("http");
      } catch(CoreException e1) {
        log.debug("Could not read http proxy configuration", e1);
      }
      if(proxyInfo != null) {
        Repository repo = new Repository("id", url); //$NON-NLS-1$
        if(!ProxyUtils.validateNonProxyHosts(proxyInfo, repo.getHost())) {
          if(proxyInfo != null) {
            ProxyServer.Protocol protocol = "https".equalsIgnoreCase(proxyInfo.getType()) ? ProxyServer.Protocol.HTTPS //$NON-NLS-1$
                : ProxyServer.Protocol.HTTP;

            sahcBuilder.setProxyProtocol(protocol);
            sahcBuilder.setProxyHost(proxyInfo.getHost());
            sahcBuilder.setProxyPort(proxyInfo.getPort());
            sahcBuilder.setProxyPrincipal(proxyInfo.getUserName());
            sahcBuilder.setProxyPassword(proxyInfo.getPassword());
          }
        }
      }

      sahcBuilder.setUrl(url);
      for(Map.Entry<String, String> param : params.entrySet()) {
        sahcBuilder.addParameter(param.getKey(), param.getValue());
      }

      SimpleAsyncHttpClient ahc = sahcBuilder.build();
      try {
        Future<Response> report = ahc.post();
        report.get();
      } catch(Exception e) {
        log.debug("Could not report usage statistics", e);
      } finally {
        ahc.close();
      }
    }
  }

  @Override
  public void start(BundleContext context) throws Exception {
    // daemon timer won't prevent JVM from shutting down.
    timer = new Timer("m2e usage stats reporter", true);

    long initialDelay = prefs.getLong(PREF_NEXTREPORT, 0) - System.currentTimeMillis();
    if(initialDelay < REPORT_MINIMAL_DELAY) {
      initialDelay = REPORT_MINIMAL_DELAY;
    }

    timer.scheduleAtFixedRate(new UsageStatsReporter(), initialDelay, REPORT_PERIOD);
  }

  @Override
  public void stop(BundleContext context) throws Exception {
    timer.cancel();
    timer = null;
  }

}
