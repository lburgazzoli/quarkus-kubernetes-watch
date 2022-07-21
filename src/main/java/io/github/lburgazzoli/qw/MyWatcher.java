package io.github.lburgazzoli.qw;

import java.util.Optional;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ListOptionsBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class MyWatcher implements Watcher<GenericKubernetesResource> {
    @Inject
    Logger log; 
    @Inject
    KubernetesClient client;

    @ConfigProperty(name = "watch.apiVersion")
    String apiVersion;
    @ConfigProperty(name = "watch.kind")
    String kind;
    @ConfigProperty(name = "watch.namespace")
    Optional<String> namespace;
    @ConfigProperty(name = "watch.timeout")
    Optional<Long> timeout;

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
        log.warnf("Closed: %s", cause.getMessage());                 
    }  

    void onStart(@Observes StartupEvent ev) {               
        log.info("The application is starting...");

        
        namespace.ifPresentOrElse(
            ns -> {
                w = client.genericKubernetesResources(apiVersion, kind).inNamespace(ns).watch(
                    timeout.isPresent() ? new ListOptionsBuilder().withTimeoutSeconds(timeout.get()).build() : new ListOptionsBuilder().build(),
                    this);
            }, 
            () -> {
                w = client.genericKubernetesResources(apiVersion, kind).inAnyNamespace().watch(
                    timeout.isPresent() ? new ListOptionsBuilder().withTimeoutSeconds(timeout.get()).build() : new ListOptionsBuilder().build(),
                    this);
            });
    }

    void onStop(@Observes ShutdownEvent ev) {               
        log.info("The application is stopping...");

        if (w != null) {
            w.close();
        }
    }
}
