/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amplifyframework.datastore;

import android.annotation.SuppressLint;
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.amplifyframework.AmplifyException;
import com.amplifyframework.api.GraphQlBehavior;
import com.amplifyframework.core.Action;
import com.amplifyframework.core.Amplify;
import com.amplifyframework.core.Consumer;
import com.amplifyframework.core.InitializationStatus;
import com.amplifyframework.core.async.Cancelable;
import com.amplifyframework.core.model.Model;
import com.amplifyframework.core.model.ModelProvider;
import com.amplifyframework.core.model.ModelSchema;
import com.amplifyframework.core.model.ModelSchemaRegistry;
import com.amplifyframework.core.model.query.predicate.QueryPredicate;
import com.amplifyframework.datastore.appsync.AppSyncClient;
import com.amplifyframework.datastore.storage.GsonStorageItemChangeConverter;
import com.amplifyframework.datastore.storage.LocalStorageAdapter;
import com.amplifyframework.datastore.storage.StorageItemChange;
import com.amplifyframework.datastore.storage.sqlite.SQLiteStorageAdapter;
import com.amplifyframework.datastore.syncengine.Orchestrator;
import com.amplifyframework.hub.HubChannel;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

import io.reactivex.Completable;

/**
 * An AWS implementation of the {@link DataStorePlugin}.
 */
public final class AWSDataStorePlugin extends DataStorePlugin<Void> {
    // Reference to an implementation of the Local Storage Adapter that
    // manages the persistence of data on-device.
    private final LocalStorageAdapter sqliteStorageAdapter;

    // A utility to convert between StorageItemChange.Record and StorageItemChange
    private final GsonStorageItemChangeConverter storageItemChangeConverter;

    // A component which synchronizes data state between the
    // local storage adapter, and a remote API
    private final Orchestrator orchestrator;

    // Keeps track of whether of not the category is initialized yet
    private final CountDownLatch categoryInitializationsPending;

    private AWSDataStorePlugin(
            @NonNull ModelProvider modelProvider,
            @NonNull ModelSchemaRegistry modelSchemaRegistry,
            @NonNull GraphQlBehavior api,
            @NonNull DataStoreConfiguration userProvidedConfiguration) {
        this.sqliteStorageAdapter = SQLiteStorageAdapter.forModels(modelSchemaRegistry, modelProvider);
        this.storageItemChangeConverter = new GsonStorageItemChangeConverter();
        this.categoryInitializationsPending = new CountDownLatch(1);
        this.orchestrator = new Orchestrator(
            modelProvider,
            modelSchemaRegistry,
            sqliteStorageAdapter,
            AppSyncClient.via(api),
            userProvidedConfiguration
        );
    }

    /**
     * Begin building a new {@link AWSDataStorePlugin} instance, by means of a new
     * {@link Builder} instance.
     * @return The first step in a sequence of builder actions.
     */
    public static BuilderSteps.ModelProviderStep builder() {
        return new Builder();
    }

    /**
     * Creates an {@link AWSDataStorePlugin} which can warehouse the model types provided by
     * the supplied {@link ModelProvider}. If remote synchronization is enabled, it will be
     * performed through {@link Amplify#API}.
     * @param modelProvider Provider of models to be usable by plugin
     * @return An {@link AWSDataStorePlugin} which warehouses the provided models
     */
    @NonNull
    @SuppressWarnings("WeakerAccess")
    public static AWSDataStorePlugin forModels(@NonNull ModelProvider modelProvider) {
        Objects.requireNonNull(modelProvider);
        return AWSDataStorePlugin.builder()
            .modelProvider(modelProvider)
            .modelSchemaRegistry(ModelSchemaRegistry.instance())
            .graphQlBehavior(Amplify.API)
            .configuration(DataStoreConfiguration.defaults())
            .build();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String getPluginKey() {
        return "awsDataStorePlugin";
    }

    /**
     * {@inheritDoc}
     */
    @SuppressLint("CheckResult")
    @Override
    public void configure(
            @Nullable JSONObject pluginConfiguration,
            @NonNull Context context
    ) {
        // AWSDataStorePlugin does not consider any values from amplifyconfiguration.json.
        // To effect the behavior of the plugin, provide an {@link DataStoreConfiguration}
        // object to the plugin, from Java, when you instantiate the plugin.

        HubChannel hubChannel = HubChannel.forCategoryType(getCategoryType());
        Amplify.Hub.subscribe(hubChannel,
            event -> InitializationStatus.SUCCEEDED.toString().equals(event.getName()),
            event -> categoryInitializationsPending.countDown()
        );
    }

    @WorkerThread
    @Override
    public void initialize(@NonNull Context context) {
        initializeStorageAdapter(context)
            .andThen(orchestrator.start())
            .blockingAwait();
    }

    /**
     * Initializes the storage adapter, and gets the result as a {@link Completable}.
     * @param context An Android Context
     * @return A Completable which will initialize the storage adapter when subscribed.
     */
    @WorkerThread
    private Completable initializeStorageAdapter(Context context) {
        return Completable.defer(() -> Completable.create(emitter ->
            sqliteStorageAdapter.initialize(context, schemaList -> emitter.onComplete(), emitter::onError)
        ));
    }

    /**
     * Terminate use of the plugin.
     * @throws AmplifyException On failure to terminate use of the plugin
     */
    @SuppressWarnings("unused")
    synchronized void terminate() throws AmplifyException {
        orchestrator.stop();
        sqliteStorageAdapter.terminate();
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public Void getEscapeHatch() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends Model> void save(
            @NonNull T item,
            @NonNull Consumer<DataStoreItemChange<T>> onItemSaved,
            @NonNull Consumer<DataStoreException> onFailureToSave) {
        save(item, null, onItemSaved, onFailureToSave);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends Model> void save(
            @NonNull T item,
            @Nullable QueryPredicate predicate,
            @NonNull Consumer<DataStoreItemChange<T>> onItemSaved,
            @NonNull Consumer<DataStoreException> onFailureToSave) {
        afterInitialization(() -> sqliteStorageAdapter.save(
            item,
            StorageItemChange.Initiator.DATA_STORE_API,
            predicate,
            recordOfSave -> {
                try {
                    onItemSaved.accept(toDataStoreItemChange(recordOfSave));
                } catch (DataStoreException dataStoreException) {
                    onFailureToSave.accept(dataStoreException);
                }
            },
            onFailureToSave
        ));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends Model> void delete(
            @NonNull T item,
            @NonNull Consumer<DataStoreItemChange<T>> onItemDeleted,
            @NonNull Consumer<DataStoreException> onFailureToDelete) {
        delete(item, null, onItemDeleted, onFailureToDelete);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends Model> void delete(
            @NonNull T item,
            @Nullable QueryPredicate predicate,
            @NonNull Consumer<DataStoreItemChange<T>> onItemDeleted,
            @NonNull Consumer<DataStoreException> onFailureToDelete) {
        afterInitialization(() -> sqliteStorageAdapter.delete(
            item,
            StorageItemChange.Initiator.DATA_STORE_API,
            recordOfDelete -> {
                try {
                    onItemDeleted.accept(toDataStoreItemChange(recordOfDelete));
                } catch (DataStoreException dataStoreException) {
                    onFailureToDelete.accept(dataStoreException);
                }
            },
            onFailureToDelete
        ));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends Model> void query(
            @NonNull Class<T> itemClass,
            @NonNull Consumer<Iterator<T>> onQueryResults,
            @NonNull Consumer<DataStoreException> onQueryFailure) {
        afterInitialization(() -> sqliteStorageAdapter.query(itemClass, onQueryResults, onQueryFailure));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends Model> void query(
            @NonNull Class<T> itemClass,
            @NonNull QueryPredicate predicate,
            @NonNull Consumer<Iterator<T>> onQueryResults,
            @NonNull Consumer<DataStoreException> onQueryFailure) {
        afterInitialization(() ->
            sqliteStorageAdapter.query(itemClass, predicate, onQueryResults, onQueryFailure));
    }

    @Override
    public void observe(
            @NonNull Consumer<Cancelable> onObservationStarted,
            @NonNull Consumer<DataStoreItemChange<? extends Model>> onDataStoreItemChange,
            @NonNull Consumer<DataStoreException> onObservationFailure,
            @NonNull Action onObservationCompleted) {
        afterInitialization(() -> onObservationStarted.accept(sqliteStorageAdapter.observe(
            storageItemChangeRecord -> {
                try {
                    onDataStoreItemChange.accept(toDataStoreItemChange(storageItemChangeRecord));
                } catch (DataStoreException dataStoreException) {
                    onObservationFailure.accept(dataStoreException);
                }
            },
            onObservationFailure,
            onObservationCompleted
        )));
    }

    @Override
    public <T extends Model> void observe(
            @NonNull Class<T> itemClass,
            @NonNull Consumer<Cancelable> onObservationStarted,
            @NonNull Consumer<DataStoreItemChange<T>> onDataStoreItemChange,
            @NonNull Consumer<DataStoreException> onObservationFailure,
            @NonNull Action onObservationCompleted) {
        afterInitialization(() -> onObservationStarted.accept(sqliteStorageAdapter.observe(
            storageItemChangeRecord -> {
                try {
                    if (!storageItemChangeRecord.getItemClass().equals(itemClass.getName())) {
                        return;
                    }
                    onDataStoreItemChange.accept(toDataStoreItemChange(storageItemChangeRecord));
                } catch (DataStoreException dataStoreException) {
                    onObservationFailure.accept(dataStoreException);
                }
            },
            onObservationFailure,
            onObservationCompleted
        )));
    }

    @Override
    public <T extends Model> void observe(
            @NonNull Class<T> itemClass,
            @NonNull String uniqueId,
            @NonNull Consumer<Cancelable> onObservationStarted,
            @NonNull Consumer<DataStoreItemChange<T>> onDataStoreItemChange,
            @NonNull Consumer<DataStoreException> onObservationFailure,
            @NonNull Action onObservationCompleted) {
        afterInitialization(() -> onObservationStarted.accept(sqliteStorageAdapter.observe(
            storageItemChangeRecord -> {
                try {
                    final DataStoreItemChange<T> dataStoreItemChange =
                        toDataStoreItemChange(storageItemChangeRecord);
                    if (!dataStoreItemChange.itemClass().equals(itemClass) ||
                        !uniqueId.equals(dataStoreItemChange.item().getId())) {
                        return;
                    }
                    onDataStoreItemChange.accept(dataStoreItemChange);
                } catch (DataStoreException dataStoreException) {
                    onObservationFailure.accept(dataStoreException);
                }
            },
            onObservationFailure,
            onObservationCompleted
        )));
    }

    @Override
    public <T extends Model> void observe(
            @NonNull Class<T> itemClass,
            @NonNull QueryPredicate selectionCriteria,
            @NonNull Consumer<Cancelable> onObservationStarted,
            @NonNull Consumer<DataStoreItemChange<T>> onDataStoreItemChange,
            @NonNull Consumer<DataStoreException> onObservationFailure,
            @NonNull Action onObservationCompleted) {
        onObservationFailure.accept(new DataStoreException("Not implemented yet, buster!", "Check back later!"));
    }

    private void afterInitialization(@NonNull final Runnable runnable) {
        Completable.fromAction(categoryInitializationsPending::await)
            .andThen(Completable.fromRunnable(runnable))
            .blockingAwait();
    }

    /**
     * Converts an {@link StorageItemChange.Record}, as recevied by the {@link LocalStorageAdapter}'s
     * {@link LocalStorageAdapter#save(Model, StorageItemChange.Initiator, Consumer, Consumer)} and
     * {@link LocalStorageAdapter#delete(Model, StorageItemChange.Initiator, Consumer, Consumer)} methods'
     * callbacks, into an {@link DataStoreItemChange}, which can be returned via the public DataStore API.
     * @param record A record of change in the storage layer
     * @param <T> Type of data that was changed
     * @return A {@link DataStoreItemChange} representing the storage change record
     */
    private <T extends Model> DataStoreItemChange<T> toDataStoreItemChange(final StorageItemChange.Record record)
        throws DataStoreException {
        return toDataStoreItemChange(record.toStorageItemChange(storageItemChangeConverter));
    }

    /**
     * Converts an {@link StorageItemChange} into an {@link DataStoreItemChange}.
     * @param storageItemChange A storage item change
     * @param <T> Type of data that was changed in the storage layer
     * @return A data store item change representing the change in storage layer
     */
    private static <T extends Model> DataStoreItemChange<T> toDataStoreItemChange(
            final StorageItemChange<T> storageItemChange) throws DataStoreException {

        final DataStoreItemChange.Initiator dataStoreItemChangeInitiator;
        switch (storageItemChange.initiator()) {
            case SYNC_ENGINE:
                dataStoreItemChangeInitiator = DataStoreItemChange.Initiator.REMOTE;
                break;
            case DATA_STORE_API:
                dataStoreItemChangeInitiator = DataStoreItemChange.Initiator.LOCAL;
                break;
            default:
                throw new DataStoreException(
                        "Unknown initiator of storage change: " + storageItemChange.initiator(),
                        AmplifyException.TODO_RECOVERY_SUGGESTION
                );
        }

        final DataStoreItemChange.Type dataStoreItemChangeType;
        switch (storageItemChange.type()) {
            case DELETE:
                dataStoreItemChangeType = DataStoreItemChange.Type.DELETE;
                break;
            case UPDATE:
                dataStoreItemChangeType = DataStoreItemChange.Type.UPDATE;
                break;
            case CREATE:
                dataStoreItemChangeType = DataStoreItemChange.Type.CREATE;
                break;
            default:
                throw new DataStoreException(
                        "Unknown type of storage change: " + storageItemChange.type(),
                        AmplifyException.TODO_RECOVERY_SUGGESTION
                );
        }

        return DataStoreItemChange.<T>builder()
            .initiator(dataStoreItemChangeInitiator)
            .item(storageItemChange.item())
            .itemClass(storageItemChange.itemClass())
            .type(dataStoreItemChangeType)
            .uuid(storageItemChange.changeId().toString())
            .build();
    }

    /**
     * Builds an {@link AWSDataStorePlugin}.
     */
    public static final class Builder implements
            BuilderSteps.ModelProviderStep, BuilderSteps.ModelSchemaRegistryStep,
            BuilderSteps.GraphQlBehaviorStep, BuilderSteps.ConfigurationStep,
            BuilderSteps.BuildStep {
        private ModelProvider modelProvider;
        private ModelSchemaRegistry modelSchemaRegistry;
        private GraphQlBehavior graphQlBehavior;
        private DataStoreConfiguration configuration;

        private Builder() {}

        @NonNull
        @Override
        public BuilderSteps.ModelSchemaRegistryStep modelProvider(@NonNull ModelProvider modelProvider) {
            Builder.this.modelProvider = Objects.requireNonNull(modelProvider);
            return Builder.this;
        }

        @NonNull
        @Override
        public BuilderSteps.GraphQlBehaviorStep modelSchemaRegistry(@NonNull ModelSchemaRegistry modelSchemaRegistry) {
            Builder.this.modelSchemaRegistry = Objects.requireNonNull(modelSchemaRegistry);
            return Builder.this;
        }

        @NonNull
        @Override
        public BuilderSteps.ConfigurationStep graphQlBehavior(@NonNull GraphQlBehavior graphQlBehavior) {
            Builder.this.graphQlBehavior = Objects.requireNonNull(graphQlBehavior);
            return Builder.this;
        }

        @NonNull
        @Override
        public BuilderSteps.BuildStep configuration(@NonNull DataStoreConfiguration configuration) {
            Builder.this.configuration = configuration;
            return Builder.this;
        }

        @Override
        public AWSDataStorePlugin build() {
            return new AWSDataStorePlugin(
                modelProvider,
                modelSchemaRegistry,
                graphQlBehavior,
                configuration
            );
        }
    }

    /**
     * This is just a namespace/bucket to keep the various Builder steps compartmentalized into one logical unit.
     */
    @SuppressWarnings("WeakerAccess") // Needs to be available to consumers in other packages.
    public static final class BuilderSteps {
        /**
         * The step where an {@link ModelProvider} is specified, and the building continues
         * on to request an {@link ModelSchemaRegistry}.
         */
        interface ModelProviderStep {
            /**
             * Configures the {@link ModelProvider} that will be used in the built {@link AWSDataStorePlugin}.
             * @param modelProvider Provider of models for the plugin under construction
             * @return The step of the builder which requires a GraphQlBehavior to be specified
             */
            ModelSchemaRegistryStep modelProvider(@NonNull ModelProvider modelProvider);
        }

        /**
         * The step where an {@link ModelSchemaRegistry} is specified, and the building
         * continues on to request an {@link GraphQlBehavior}.
         */
        interface ModelSchemaRegistryStep {
            /**
             * Configures the {@link ModelSchemaRegistry} into which schema will be kept at runtime.
             * @param modelSchemaRegistry The registry into which {@link ModelSchema} will be stored
             * @return The next step in the build process
             */
            GraphQlBehaviorStep modelSchemaRegistry(@NonNull ModelSchemaRegistry modelSchemaRegistry);
        }

        /**
         * The step where an {@link GraphQlBehavior} is specified. After this, all components are
         * specified, and the building precedes to offer the {@link BuildStep#build()} as a final
         * action.
         */
        interface GraphQlBehaviorStep {
            /**
             * Configures the {@link GraphQlBehavior} that is used to talk to the AppSync endpoint.
             * This component will only be used if remote model synchronization is enabled in the plugin.
             * @param graphQlBehavior A GraphQL client which can be used to talk to AppSync.
             * @return The step of the Builder where an AWSDataStorePlugin finally gets constructed
             */
            ConfigurationStep graphQlBehavior(@NonNull GraphQlBehavior graphQlBehavior);
        }

        /**
         * The step of the building process where the user provides a configuration for the DataStore.
         */
        interface ConfigurationStep {
            /**
             * Configures the {@link DataStoreConfiguration} that will be used by the built {@link AWSDataStorePlugin}.
             * @param configuration Configuration to use
             * @return The final step of the building, where the {@link AWSDataStorePlugin} is  built.
             */
            BuildStep configuration(@NonNull DataStoreConfiguration configuration);
        }

        /**
         * The step where an {@link AWSDataStorePlugin} is built and returned.
         */
        interface BuildStep {
            /**
             * Builds an {@link AWSDataStorePlugin} using the options provided to the {@link Builder}.
             * @return A new {@link AWSDataStorePlugin} instance
             */
            AWSDataStorePlugin build();
        }
    }
}
