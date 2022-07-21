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

    private Watch w;
    private SharedIndexInformer<GenericKubernetesResource> i;
        
    void onStart(@Observes StartupEvent ev) {   
        if (mode.equals("watch") || mode.equals("all")) {    

            log.info("The watcher is starting...");
            
            namespace.ifPresentOrElse(
                ns -> {
                    w = client.genericKubernetesResources(apiVersion, kind).inNamespace(ns).watch(
                        timeout.isPresent() 
                            ? new ListOptionsBuilder().withTimeoutSeconds(timeout.get()).build() 
                            : new ListOptionsBuilder().build(),
                        new ResourceWatcher());
                }, 
                () -> {
                    w = client.genericKubernetesResources(apiVersion, kind).inAnyNamespace().watch(
                        timeout.isPresent() 
                            ? new ListOptionsBuilder().withTimeoutSeconds(timeout.get()).build() 
                            : new ListOptionsBuilder().build(),
                        new ResourceWatcher());
                });
        }

        if (mode.equals("inform") || mode.equals("all")) {   
            log.info("The informer is starting...");

            namespace.ifPresentOrElse(
                ns -> {
                    i = client.genericKubernetesResources(apiVersion, kind).inNamespace(ns).inform(new ResourceHandler());
                }, 
                () -> {
                    i = client.genericKubernetesResources(apiVersion, kind).inAnyNamespace().inform(new ResourceHandler());
                }); 
        }
    }

    void onStop(@Observes ShutdownEvent ev) {               
        log.info("The application is stopping...");

        try {
            if (w != null) {
                w.close();
            }
        } catch (Exception e) {
            // ignored            
        }

        try {
            if (i != null) {
                i.close();
            }
        } catch (Exception e) {
            // ignored            
        }
    }

    private class ResourceWatcher implements Watcher<GenericKubernetesResource> {

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
    }

    private class ResourceHandler implements ResourceEventHandler<GenericKubernetesResource> {
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
    }
}
