/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.api.impl.fulltext;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.kernel.AvailabilityGuard;
import org.neo4j.logging.Log;
import org.neo4j.scheduler.JobScheduler;

/**
 * Provider class that manages and provides fulltext indices. This is the main entry point for the fulltext addon.
 */
public class FulltextProvider implements AutoCloseable
{
    public static final String LUCENE_FULLTEXT_ADDON_INTERNAL_ID = "__lucene__fulltext__addon__internal__id__";
    private final GraphDatabaseService db;
    private final Log log;
    private final FulltextTransactionEventUpdater fulltextTransactionEventUpdater;
    private final Set<String> nodeProperties;
    private final Set<String> relationshipProperties;
    private final Set<WritableFulltext> writableNodeIndices;
    private final Set<WritableFulltext> writableRelationshipIndices;
    private final Map<String,LuceneFulltext> nodeIndices;
    private final Map<String,LuceneFulltext> relationshipIndices;
    private final FulltextUpdateApplier applier;

    /**
     * Creates a provider of fulltext indices for the given database. This is the entry point for all fulltext index operations.
     * @param db Database that this provider should work with.
     * @param log For logging errors.
     * @param availabilityGuard Used for waiting with populating the index until the database is available.
     * @param scheduler For background work.
     */
    public FulltextProvider( GraphDatabaseService db, Log log, AvailabilityGuard availabilityGuard,
                             JobScheduler scheduler )
    {
        this.db = db;
        this.log = log;
        applier = new FulltextUpdateApplier( log, availabilityGuard, scheduler );
        applier.start();
        fulltextTransactionEventUpdater = new FulltextTransactionEventUpdater( this, log, applier );
        nodeProperties = new HashSet<>();
        relationshipProperties = new HashSet<>();
        writableNodeIndices = new HashSet<>();
        writableRelationshipIndices = new HashSet<>();
        nodeIndices = new HashMap<>();
        relationshipIndices = new HashMap<>();
    }

    public void init() throws IOException
    {
        for ( WritableFulltext index : writableNodeIndices )
        {
            applier.populateNodes( index, db );
        }
        for ( WritableFulltext index : writableRelationshipIndices )
        {
            applier.populateRelationships( index, db );
        }
        db.registerTransactionEventHandler( fulltextTransactionEventUpdater );
    }

    /**
     * Wait for the asynchronous background population, if one is on-going, to complete.
     *
     * Such population, where the entire store is scanned for data to write to the index, will be started if the index
     * needs to recover after an unclean shut-down, or a configuration change.
     * @throws IOException If it was not possible to wait for the population to finish, for some reason.
     */
    public void awaitPopulation() throws IOException
    {
        applier.writeBarrier().awaitCompletion();
    }

    /**
     * Closes the provider and all associated resources.
     */
    @Override
    public void close()
    {
        db.unregisterTransactionEventHandler( fulltextTransactionEventUpdater );
        applier.stop();
        nodeIndices.values().forEach( luceneFulltextIndex ->
        {
            try
            {
                luceneFulltextIndex.close();
            }
            catch ( IOException e )
            {
                log.error( "Unable to close fulltext node index.", e );
            }
        } );
        relationshipIndices.values().forEach( luceneFulltextIndex ->
        {
            try
            {
                luceneFulltextIndex.close();
            }
            catch ( IOException e )
            {
                log.error( "Unable to close fulltext relationship index.", e );
            }
        } );
    }

    void register( LuceneFulltext fulltextIndex ) throws IOException
    {
        fulltextIndex.open();
        if ( fulltextIndex.getType() == FulltextIndexType.NODES )
        {
            nodeIndices.put( fulltextIndex.getIdentifier(), fulltextIndex );
            writableNodeIndices.add( new WritableFulltext( fulltextIndex ) );
            nodeProperties.addAll( fulltextIndex.getProperties() );
        }
        else
        {
            relationshipIndices.put( fulltextIndex.getIdentifier(), fulltextIndex );
            writableRelationshipIndices.add( new WritableFulltext( fulltextIndex ) );
            relationshipProperties.addAll( fulltextIndex.getProperties() );
        }
    }

    String[] getNodeProperties()
    {
        return nodeProperties.toArray( new String[0] );
    }

    String[] getRelationshipProperties()
    {
        return relationshipProperties.toArray( new String[0] );
    }

    Set<WritableFulltext> writableNodeIndices()
    {
        return Collections.unmodifiableSet( writableNodeIndices );
    }

    Set<WritableFulltext> writableRelationshipIndices()
    {
        return Collections.unmodifiableSet( writableRelationshipIndices );
    }

    /**
     * Returns a reader for the specified index.
     *
     * @param identifier Identifier for the index.
     * @param type Type of the index.
     * @return A {@link ReadOnlyFulltext} for the index, or null if no such index is found.
     * @throws IOException
     */
    public ReadOnlyFulltext getReader( String identifier, FulltextIndexType type ) throws IOException
    {
        if ( type == FulltextIndexType.NODES )
        {
            return nodeIndices.get( identifier ).getIndexReader();
        }
        else
        {
            return relationshipIndices.get( identifier ).getIndexReader();
        }
    }

    /**
     * Fulltext index type.
     */
    public enum FulltextIndexType
    {
        NODES
                {
                    @Override
                    public String toString()
                    {
                        return "Nodes";
                    }
                },
        RELATIONSHIPS
                {
                    @Override
                    public String toString()
                    {
                        return "Relationships";
                    }
                }
    }
}
