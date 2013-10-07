/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * JBoss, Home of Professional Open Source
 * Copyright 2013 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */
package org.hibernate.ogm.massindex.batchindexing;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

import org.hibernate.CacheMode;
import org.hibernate.SessionFactory;
import org.hibernate.ogm.dialect.GridDialect;
import org.hibernate.ogm.util.impl.Log;
import org.hibernate.ogm.util.impl.LoggerFactory;
import org.hibernate.search.backend.PurgeAllLuceneWork;
import org.hibernate.search.backend.impl.batch.BatchBackend;
import org.hibernate.search.batchindexing.MassIndexerProgressMonitor;
import org.hibernate.search.engine.spi.SearchFactoryImplementor;
import org.hibernate.search.exception.ErrorHandler;

/**
 * Makes sure that several different BatchIndexingWorkspace(s)
 * can be started concurrently, sharing the same batch-backend
 * and IndexWriters.
 *
 * @author Sanne Grinovero
 * @author Davide D'Alto <davide@hibernate.org>
 */
public class BatchCoordinator implements Runnable {

	private static final Log log = LoggerFactory.make();

	private final Class<?>[] rootEntities; // entity types to reindex excluding all subtypes of each-other
	private final SearchFactoryImplementor searchFactoryImplementor;
	private final SessionFactory sessionFactory;
	private final CacheMode cacheMode;
	private final boolean optimizeAtEnd;
	private final boolean purgeAtStart;
	private final boolean optimizeAfterPurge;
	private final CountDownLatch endAllSignal;
	private final MassIndexerProgressMonitor monitor;
	private final ErrorHandler errorHandler;

	private final GridDialect gridDialect;

	public BatchCoordinator(GridDialect gridDialect, Set<Class<?>> rootEntities, SearchFactoryImplementor searchFactoryImplementor,
			SessionFactory sessionFactory, CacheMode cacheMode, boolean optimizeAtEnd, boolean purgeAtStart, boolean optimizeAfterPurge,
			MassIndexerProgressMonitor monitor) {
		this.gridDialect = gridDialect;
		this.rootEntities = rootEntities.toArray( new Class<?>[rootEntities.size()] );
		this.searchFactoryImplementor = searchFactoryImplementor;
		this.sessionFactory = sessionFactory;
		this.cacheMode = cacheMode;
		this.optimizeAtEnd = optimizeAtEnd;
		this.purgeAtStart = purgeAtStart;
		this.optimizeAfterPurge = optimizeAfterPurge;
		this.monitor = monitor;
		this.endAllSignal = new CountDownLatch( rootEntities.size() );
		this.errorHandler = searchFactoryImplementor.getErrorHandler();
	}

	@Override
	public void run() {
		try {
			final BatchBackend backend = searchFactoryImplementor.makeBatchBackend( monitor );
			try {
				beforeBatch( backend ); // purgeAll and pre-optimize activities
				doBatchWork( backend );
				afterBatch( backend );
			}
			catch ( InterruptedException e ) {
				log.interruptedBatchIndexing();
				Thread.currentThread().interrupt();
			}
			finally {
				monitor.indexingCompleted();
			}
		}
		catch ( RuntimeException re ) {
			// each batch processing stage is already supposed to properly handle any kind
			// of exception, still since this is possibly an async operation we need a safety
			// for the unexpected exceptions
			errorHandler.handleException( "ERROR", re );
		}
	}

	/**
	 * Will spawn a thread for each type in rootEntities, they will all re-join
	 * on endAllSignal when finished.
	 *
	 * @param backend
	 *
	 * @throws InterruptedException
	 *             if interrupted while waiting for endAllSignal.
	 */
	private void doBatchWork(BatchBackend backend) throws InterruptedException {
		ExecutorService executor = Executors.newFixedThreadPool( rootEntities.length, "BatchIndexingWorkspace" );
		for ( Class<?> type : rootEntities ) {
			executor.execute( new BatchIndexingWorkspace( gridDialect, searchFactoryImplementor, sessionFactory, type,
					cacheMode, endAllSignal, monitor, backend ) );
		}
		executor.shutdown();
		endAllSignal.await(); // waits for the executor to finish
	}

	/**
	 * Operations to do after all subthreads finished their work on index
	 *
	 * @param backend
	 */
	private void afterBatch(BatchBackend backend) {
		Set<Class<?>> targetedClasses = searchFactoryImplementor.getIndexedTypesPolymorphic( rootEntities );
		if ( this.optimizeAtEnd ) {
			backend.optimize( targetedClasses );
		}
		backend.flush( targetedClasses );
	}

	/**
	 * Optional operations to do before the multiple-threads start indexing
	 *
	 * @param backend
	 */
	private void beforeBatch(BatchBackend backend) {
		if ( this.purgeAtStart ) {
			// purgeAll for affected entities
			Set<Class<?>> targetedClasses = searchFactoryImplementor.getIndexedTypesPolymorphic( rootEntities );
			for ( Class<?> clazz : targetedClasses ) {
				// needs do be in-sync work to make sure we wait for the end of it.
				backend.doWorkInSync( new PurgeAllLuceneWork( clazz ) );
			}
			if ( this.optimizeAfterPurge ) {
				backend.optimize( targetedClasses );
			}
		}
	}

}
