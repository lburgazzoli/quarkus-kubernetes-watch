package io.github.lburgazzoli.qw;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ListOptionsBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class MyWatcher {
    @Inject
    Logger log;
    @Inject
    KubernetesClient client;

    @ConfigProperty(name = "watch.mode", defaultValue = "watch")
    String mode;
    @ConfigProperty(name = "watch.apiVersion")
    String apiVersion;
    @ConfigProperty(name = "watch.kind")
    String kind;
    @ConfigProperty(name = "watch.namespace")
    Optional<String> namespace;
    @ConfigProperty(name = "watch.timeout")
    Optional<Long> timeout;

    private ResourceWatcher w;
    private ResourceHandler i;
    private ScheduledExecutorService executor;

    void onStart(@Observes StartupEvent ev) {
        executor = Executors.newSingleThreadScheduledExecutor();

        if (mode.equals("watch") || mode.equals("all")) {
            w = new ResourceWatcher();
            w.start();
        }

        if (mode.equals("inform") || mode.equals("all")) {
            i = new ResourceHandler();
            i.start();
        }
    }

    void onStop(@Observes ShutdownEvent ev) {
        log.info("The application is stopping...");

        if (w != null) {
            w.close();
        }

        if (i != null) {
            i.close();
        }

        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private class ResourceWatcher implements Watcher<GenericKubernetesResource>, AutoCloseable {
        private Watch w;

        @Override
        public void eventReceived(Action action, GenericKubernetesResource resource) {
            log.infof("Action: %s, Namespace: %s, Name: %s",
                action.name(),
                resource.getMetadata().getNamespace(),
                resource.getMetadata().getName());
        }

        @Override
        public void onClose(WatcherException cause) {
            if (cause == null) {
                return;
            }

            log.warnf("Closed: %s", cause.getMessage());

            if (cause.isHttpGone()) {
                log.infof("Received recoverable error for watch (message: %s)", cause.getMessage());

                close();

                log.info("Try to reconnect in 5 seconds");
                executor.schedule(this::start, 5, TimeUnit.SECONDS);
            } else {
                // Note that this should not happen normally, since fabric8 client handles reconnect.
                // In case it tries to reconnect this method is not called.
                log.error("Unexpected error happened with watch. Will exit.", cause);
            }
        }

        public void start() {
            log.info("The watcher is starting...");

            try {
                namespace.ifPresentOrElse(
                    ns -> {
                        w = client.genericKubernetesResources(apiVersion, kind).inNamespace(ns).watch(
                            timeout.isPresent()
                                ? new ListOptionsBuilder().withTimeoutSeconds(timeout.get()).build()
                                : new ListOptionsBuilder().build(),
                            this);
                    },
                    () -> {
                        w = client.genericKubernetesResources(apiVersion, kind).inAnyNamespace().watch(
                            timeout.isPresent()
                                ? new ListOptionsBuilder().withTimeoutSeconds(timeout.get()).build()
                                : new ListOptionsBuilder().build(),
                            this);
                    });
            } catch (KubernetesClientException e) {
                log.infof("Received error while staring watcher, try to reconnect in 5 seconds  (message: %s)", e.getMessage());
                executor.schedule(this::start, 5, TimeUnit.SECONDS);
            }
        }

        @Override
        public void close() {
            try {
                if (w != null) {
                    w.close();
                }
            } catch (Exception e) {
                log.error("Unexpected error happened when closing watcher.", e);
            }
        }
    }

    private class ResourceHandler implements ResourceEventHandler<GenericKubernetesResource>, AutoCloseable {
        private SharedIndexInformer<GenericKubernetesResource> i;

        @Override
        public void onAdd(GenericKubernetesResource resource) {
            log.infof("Add, Namespace: %s, Name: %s",
                resource.getMetadata().getNamespace(),
                resource.getMetadata().getName());

        }

        @Override
        public void onUpdate(GenericKubernetesResource oldObj, GenericKubernetesResource resource) {
            log.infof("Update, Namespace: %s, Name: %s",
                resource.getMetadata().getNamespace(),
                resource.getMetadata().getName());

        }

        @Override
        public void onDelete(GenericKubernetesResource resource, boolean deletedFinalStateUnknown) {
            log.infof("Delete, Namespace: %s, Name: %s",
                resource.getMetadata().getNamespace(),
                resource.getMetadata().getName());

        }

        public void start() {
            log.info("The informer is starting...");

            namespace.ifPresentOrElse(
                ns -> {
                    i = client.genericKubernetesResources(apiVersion, kind).inNamespace(ns).inform(this);
                },
                () -> {
                    i = client.genericKubernetesResources(apiVersion, kind).inAnyNamespace().inform(this);
                });
        }

        @Override
        public void close() {
            try {
                if (i != null) {
                    i.close();
                }
            } catch (Exception e) {
                log.error("Unexpected error happened when closing informer.", e);
            }
        }
    }
}
