/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphdb;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

import org.neo4j.collection.Dependencies;
import org.neo4j.common.DependencyResolver;
import org.neo4j.dbms.database.DatabaseManagementService;
import org.neo4j.exceptions.UnderlyingStorageException;
import org.neo4j.graphdb.factory.module.id.IdContextFactory;
import org.neo4j.graphdb.factory.module.id.IdContextFactoryBuilder;
import org.neo4j.graphdb.mockfs.EphemeralFileSystemAbstraction;
import org.neo4j.helpers.collection.Iterables;
import org.neo4j.internal.id.DefaultIdGeneratorFactory;
import org.neo4j.internal.id.IdGenerator;
import org.neo4j.internal.id.IdType;
import org.neo4j.internal.id.configuration.CommunityIdTypeConfigurationProvider;
import org.neo4j.internal.kernel.api.LabelSet;
import org.neo4j.internal.kernel.api.NodeCursor;
import org.neo4j.internal.kernel.api.PropertyCursor;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.impl.core.ThreadToStatementContextBridge;
import org.neo4j.kernel.impl.scheduler.JobSchedulerFactory;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.test.TestDatabaseManagementServiceBuilder;
import org.neo4j.test.extension.ImpermanentDbmsExtension;
import org.neo4j.test.extension.Inject;

import static java.util.stream.Collectors.toSet;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;
import static org.neo4j.graphdb.Label.label;
import static org.neo4j.helpers.collection.Iterables.asList;
import static org.neo4j.helpers.collection.Iterables.map;
import static org.neo4j.helpers.collection.Iterators.asSet;
import static org.neo4j.test.mockito.matcher.Neo4jMatchers.hasLabel;
import static org.neo4j.test.mockito.matcher.Neo4jMatchers.hasLabels;
import static org.neo4j.test.mockito.matcher.Neo4jMatchers.hasNoLabels;
import static org.neo4j.test.mockito.matcher.Neo4jMatchers.hasNoNodes;
import static org.neo4j.test.mockito.matcher.Neo4jMatchers.hasNodes;
import static org.neo4j.test.mockito.matcher.Neo4jMatchers.inTx;

@ImpermanentDbmsExtension
class LabelsAcceptanceTest
{
    @Inject
    private GraphDatabaseAPI db;

    private enum Labels implements Label
    {
        MY_LABEL,
        MY_OTHER_LABEL
    }

    /** https://github.com/neo4j/neo4j/issues/1279 */
    @Test
    void shouldInsertLabelsWithoutDuplicatingThem()
    {
        // Given
        Node node;

        // When
        try ( Transaction tx = db.beginTx() )
        {
            node = db.createNode();
            node.addLabel( Labels.MY_LABEL );

            tx.success();
        }

        // POST "FOOBAR"
        try ( Transaction tx = db.beginTx() )
        {
            node.addLabel( Labels.MY_LABEL );
            tx.success();
        }

        // POST ["BAZQUX"]
        try ( Transaction tx = db.beginTx() )
        {
            node.addLabel( label( "BAZQUX" ) );
            tx.success();
        }
        // PUT ["BAZQUX"]
        try ( Transaction tx = db.beginTx() )
        {
            for ( Label label : node.getLabels() )
            {
                node.removeLabel( label );
            }
            node.addLabel( label( "BAZQUX" ) );
            tx.success();
        }

        // GET
        List<Label> labels = new ArrayList<>();
        try ( Transaction tx = db.beginTx() )
        {
            for ( Label label : node.getLabels() )
            {
                labels.add( label );
            }
            tx.success();
        }
        assertEquals( 1, labels.size(), labels.toString() );
        assertEquals( "BAZQUX", labels.get( 0 ).name() );
    }

    @Test
    void addingALabelUsingAValidIdentifierShouldSucceed()
    {
        // Given
        Node myNode;

        // When
        try ( Transaction tx = db.beginTx() )
        {
            myNode = db.createNode();
            myNode.addLabel( Labels.MY_LABEL );

            tx.success();
        }

        // Then
        assertThat( "Label should have been added to node", myNode,
                inTx( db, hasLabel( Labels.MY_LABEL ) ) );
    }

    @Test
    void addingALabelUsingAnInvalidIdentifierShouldFail()
    {
        // When I set an empty label
        try ( Transaction tx = db.beginTx() )
        {
            db.createNode().addLabel( label( "" ) );
            fail( "Should have thrown exception" );
        }
        catch ( ConstraintViolationException ex )
        {   // Happy
        }

        // And When I set a null label
        try ( Transaction tx2 = db.beginTx() )
        {
            db.createNode().addLabel( () -> null );
            fail( "Should have thrown exception" );
        }
        catch ( ConstraintViolationException ex )
        {   // Happy
        }
    }

    @Test
    void addingALabelThatAlreadyExistsBehavesAsNoOp()
    {
        // Given
        Node myNode;

        // When
        try ( Transaction tx = db.beginTx() )
        {
            myNode = db.createNode();
            myNode.addLabel( Labels.MY_LABEL );
            myNode.addLabel( Labels.MY_LABEL );

            tx.success();
        }

        // Then
        assertThat( "Label should have been added to node", myNode,
                inTx( db, hasLabel( Labels.MY_LABEL ) ) );
    }

    @Test
    void oversteppingMaxNumberOfLabelsShouldFailGracefully() throws IOException
    {
        try ( EphemeralFileSystemAbstraction fileSystem = new EphemeralFileSystemAbstraction() )
        {
            // Given
            Dependencies dependencies = new Dependencies();
            dependencies.satisfyDependencies( createIdContextFactory( fileSystem ) );

            DatabaseManagementService managementService = new TestDatabaseManagementServiceBuilder().setFileSystem( fileSystem ).setExternalDependencies(
                    dependencies ).impermanent().build();

            GraphDatabaseService graphDatabase = managementService.database( DEFAULT_DATABASE_NAME );

            // When
            try ( Transaction tx = graphDatabase.beginTx() )
            {
                graphDatabase.createNode().addLabel( Labels.MY_LABEL );
                fail( "Should have thrown exception" );
            }
            catch ( ConstraintViolationException ex )
            {   // Happy
            }

            managementService.shutdown();
        }
    }

    @Test
    void removingCommittedLabel()
    {
        // Given
        Label label = Labels.MY_LABEL;
        Node myNode = createNode( db, label );

        // When
        try ( Transaction tx = db.beginTx() )
        {
            myNode.removeLabel( label );
            tx.success();
        }

        // Then
        assertThat( myNode, not( inTx( db, hasLabel( label ) ) ) );
    }

    @Test
    void createNodeWithLabels()
    {
        // WHEN
        Node node;
        try ( Transaction tx = db.beginTx() )
        {
            node = db.createNode( Labels.values() );
            tx.success();
        }

        // THEN

        Set<String> names = Stream.of( Labels.values() ).map( Labels::name ).collect( toSet() );
        assertThat( node, inTx( db, hasLabels( names ) ) );
    }

    @Test
    void removingNonExistentLabel()
    {
        // Given
        Label label = Labels.MY_LABEL;

        // When
        Node myNode;
        try ( Transaction tx = db.beginTx() )
        {
            myNode = db.createNode();
            myNode.removeLabel( label );
            tx.success();
        }

        // THEN
        assertThat( myNode, not( inTx( db, hasLabel( label ) ) ) );
    }

    @Test
    void removingExistingLabelFromUnlabeledNode()
    {
        // Given
        Label label = Labels.MY_LABEL;
        createNode( db, label );
        Node myNode = createNode( db );

        // When
        try ( Transaction tx = db.beginTx() )
        {
            myNode.removeLabel( label );
            tx.success();
        }

        // THEN
        assertThat( myNode, not( inTx( db, hasLabel( label ) ) ) );
    }

    @Test
    void removingUncommittedLabel()
    {
        // Given
        Label label = Labels.MY_LABEL;

        // When
        Node myNode;
        try ( Transaction tx = db.beginTx() )
        {
            myNode = db.createNode();
            myNode.addLabel( label );
            myNode.removeLabel( label );

            // THEN
            assertFalse( myNode.hasLabel( label ) );

            tx.success();
        }
    }

    @Test
    void shouldBeAbleToListLabelsForANode()
    {
        // GIVEN
        Node node;
        Set<String> expected = asSet( Labels.MY_LABEL.name(), Labels.MY_OTHER_LABEL.name() );
        try ( Transaction tx = db.beginTx() )
        {
            node = db.createNode();
            for ( String label : expected )
            {
                node.addLabel( label( label ) );
            }
            tx.success();
        }

        assertThat( node, inTx( db, hasLabels( expected ) ) );
    }

    @Test
    void shouldReturnEmptyListIfNoLabels()
    {
        // GIVEN
        Node node = createNode( db );

        // WHEN THEN
        assertThat( node, inTx( db, hasNoLabels() ) );
    }

    @Test
    void getNodesWithLabelCommitted()
    {
        // When
        Node node;
        try ( Transaction tx = db.beginTx() )
        {
            node = db.createNode();
            node.addLabel( Labels.MY_LABEL );
            tx.success();
        }

        // THEN
        assertThat( db, inTx( db, hasNodes( Labels.MY_LABEL, node ) ) );
        assertThat( db, inTx( db, hasNoNodes( Labels.MY_OTHER_LABEL ) ) );
    }

    @Test
    void getNodesWithLabelsWithTxAddsAndRemoves()
    {
        // GIVEN
        Node node1 = createNode( db, Labels.MY_LABEL, Labels.MY_OTHER_LABEL );
        Node node2 = createNode( db, Labels.MY_LABEL, Labels.MY_OTHER_LABEL );

        // WHEN
        Node node3;
        Set<Node> nodesWithMyLabel;
        Set<Node> nodesWithMyOtherLabel;
        try ( Transaction tx = db.beginTx() )
        {
            node3 = db.createNode( Labels.MY_LABEL );
            node2.removeLabel( Labels.MY_LABEL );
            // extracted here to be asserted below
            nodesWithMyLabel = asSet( db.findNodes( Labels.MY_LABEL ) );
            nodesWithMyOtherLabel = asSet( db.findNodes( Labels.MY_OTHER_LABEL ) );
            tx.success();
        }

        // THEN
        assertEquals( asSet( node1, node3 ), nodesWithMyLabel );
        assertEquals( asSet( node1, node2 ), nodesWithMyOtherLabel );
    }

    @Test
    void shouldListAllExistingLabels()
    {
        // Given
        createNode( db, Labels.MY_LABEL, Labels.MY_OTHER_LABEL );
        List<Label> labels;

        // When
        try ( Transaction tx = db.beginTx() )
        {
            labels = asList( db.getAllLabels() );
        }

        // Then
        assertEquals( 2, labels.size() );
        assertThat( map( Label::name, labels ), hasItems( Labels.MY_LABEL.name(), Labels.MY_OTHER_LABEL.name() ) );
    }

    @Test
    void shouldListAllLabelsInUse()
    {
        // Given
        createNode( db, Labels.MY_LABEL );
        Node node = createNode( db, Labels.MY_OTHER_LABEL );
        try ( Transaction tx = db.beginTx() )
        {
            node.delete();
            tx.success();
        }
        List<Label> labels;

        // When
        try ( Transaction tx = db.beginTx() )
        {
            labels = asList( db.getAllLabelsInUse() );
        }

        // Then
        assertEquals( 1, labels.size() );
        assertThat( map( Label::name, labels ), hasItems( Labels.MY_LABEL.name() ) );
    }

    @Test
    void deleteAllNodesAndTheirLabels()
    {
        // GIVEN
        final Label label = label( "A" );
        try ( Transaction tx = db.beginTx() )
        {
            Node node = db.createNode();
            node.addLabel( label );
            node.setProperty( "name", "bla" );
            tx.success();
        }

        // WHEN
        try ( Transaction tx = db.beginTx() )
        {
            for ( final Node node : db.getAllNodes() )
            {
                node.removeLabel( label ); // remove Label ...
                node.delete(); // ... and afterwards the node
            }
            tx.success();
        } // tx.close(); - here comes the exception

        // THEN
        try ( Transaction transaction = db.beginTx() )
        {
            assertEquals( 0, Iterables.count( db.getAllNodes() ) );
        }
    }

    @Test
    void removingLabelDoesNotBreakPreviouslyCreatedLabelsIterator()
    {
        // GIVEN
        Label label1 = label( "A" );
        Label label2 = label( "B" );

        try ( Transaction tx = db.beginTx() )
        {
            Node node = db.createNode( label1, label2 );

            for ( Label next : node.getLabels() )
            {
                node.removeLabel( next );
            }
            tx.success();
        }
    }

    @Test
    void removingPropertyDoesNotBreakPreviouslyCreatedNodePropertyKeysIterator()
    {
        // GIVEN
        try ( Transaction tx = db.beginTx() )
        {
            Node node = db.createNode();
            node.setProperty( "name", "Horst" );
            node.setProperty( "age", "72" );

            Iterator<String> iterator = node.getPropertyKeys().iterator();

            while ( iterator.hasNext() )
            {
                node.removeProperty( iterator.next() );
            }
            tx.success();
        }
    }

    @Test
    void shouldCreateNodeWithLotsOfLabelsAndThenRemoveMostOfThem()
    {
        // given
        final int TOTAL_NUMBER_OF_LABELS = 200;
        final int NUMBER_OF_PRESERVED_LABELS = 20;
        Node node;
        try ( Transaction tx = db.beginTx() )
        {
            node = db.createNode();
            for ( int i = 0; i < TOTAL_NUMBER_OF_LABELS; i++ )
            {
                node.addLabel( label( "label:" + i ) );
            }

            tx.success();
        }

        // when
        try ( Transaction tx = db.beginTx() )
        {
            for ( int i = NUMBER_OF_PRESERVED_LABELS; i < TOTAL_NUMBER_OF_LABELS; i++ )
            {
                node.removeLabel( label( "label:" + i ) );
            }

            tx.success();
        }

        // then
        try ( Transaction transaction = db.beginTx() )
        {
            List<String> labels = new ArrayList<>();
            for ( Label label : node.getLabels() )
            {
                labels.add( label.name() );
            }
            assertEquals( NUMBER_OF_PRESERVED_LABELS, labels.size(), "labels on node: " + labels );
        }
    }

    @Test
    void shouldAllowManyLabelsAndPropertyCursor()
    {
        int propertyCount = 10;
        int labelCount = 15;

        Node node;
        try ( Transaction tx = db.beginTx() )
        {
            node = db.createNode();
            for ( int i = 0; i < propertyCount; i++ )
            {
                node.setProperty( "foo" + i, "bar" );
            }
            for ( int i = 0; i < labelCount; i++ )
            {
                node.addLabel( label( "label" + i ) );
            }
            tx.success();
        }

        Set<Integer> seenProperties = new HashSet<>();
        Set<Integer> seenLabels = new HashSet<>();
        try ( Transaction tx = db.beginTx() )
        {
            DependencyResolver resolver = db.getDependencyResolver();
            ThreadToStatementContextBridge bridge = resolver.resolveDependency( ThreadToStatementContextBridge.class );
            KernelTransaction ktx = bridge.getKernelTransactionBoundToThisThread( true );
            try ( NodeCursor nodes = ktx.cursors().allocateNodeCursor();
                  PropertyCursor propertyCursor = ktx.cursors().allocatePropertyCursor() )
            {
                ktx.dataRead().singleNode( node.getId(), nodes );
                while ( nodes.next() )
                {
                    nodes.properties( propertyCursor );
                    while ( propertyCursor.next() )
                    {
                        seenProperties.add( propertyCursor.propertyKey() );
                    }

                    LabelSet labels = nodes.labels();
                    for ( int i = 0; i < labels.numberOfLabels(); i++ )
                    {
                        seenLabels.add( labels.label( i ) );
                    }
                }
            }
            tx.success();
        }

        assertEquals( propertyCount, seenProperties.size() );
        assertEquals( labelCount, seenLabels.size() );
    }

    @Test
    void nodeWithManyLabels()
    {
        int labels = 500;
        int halveLabels = labels / 2;
        long nodeId = createNode( db ).getId();

        addLabels( nodeId, 0, halveLabels );
        addLabels( nodeId, halveLabels, halveLabels );

        verifyLabels( nodeId, 0, labels );

        removeLabels( nodeId, halveLabels, halveLabels );
        verifyLabels( nodeId, 0, halveLabels );

        removeLabels( nodeId, 0, halveLabels - 2 );
        verifyLabels( nodeId, halveLabels - 2, 2 );
    }

    private void addLabels( long nodeId, int startLabelIndex, int count )
    {
        try ( Transaction tx = db.beginTx() )
        {
            Node node = db.getNodeById( nodeId );
            int endLabelIndex = startLabelIndex + count;
            for ( int i = startLabelIndex; i < endLabelIndex; i++ )
            {
                node.addLabel( labelWithIndex( i ) );
            }
            tx.success();
        }
    }

    private void verifyLabels( long nodeId, int startLabelIndex, int count )
    {
        try ( Transaction tx = db.beginTx() )
        {
            Node node = db.getNodeById( nodeId );
            Set<String> labelNames = Iterables.asList( node.getLabels() )
                    .stream()
                    .map( Label::name )
                    .sorted()
                    .collect( toSet() );

            assertEquals( count, labelNames.size() );
            int endLabelIndex = startLabelIndex + count;
            for ( int i = startLabelIndex; i < endLabelIndex; i++ )
            {
                assertTrue( labelNames.contains( labelName( i ) ) );
            }
            tx.success();
        }
    }

    private void removeLabels( long nodeId, int startLabelIndex, int count )
    {
        try ( Transaction tx = db.beginTx() )
        {
            Node node = db.getNodeById( nodeId );
            int endLabelIndex = startLabelIndex + count;
            for ( int i = startLabelIndex; i < endLabelIndex; i++ )
            {
                node.removeLabel( labelWithIndex( i ) );
            }
            tx.success();
        }
    }

    private static Label labelWithIndex( int index )
    {
        return label( labelName( index ) );
    }

    private static String labelName( int index )
    {
        return "Label-" + index;
    }

    private static Node createNode( GraphDatabaseService db, Label... labels )
    {
        try ( Transaction tx = db.beginTx() )
        {
            Node node = db.createNode( labels );
            tx.success();
            return node;
        }
    }

    private IdContextFactory createIdContextFactory( FileSystemAbstraction fileSystem )
    {
        return IdContextFactoryBuilder.of( new CommunityIdTypeConfigurationProvider(), JobSchedulerFactory.createScheduler() ).withIdGenerationFactoryProvider(
                any -> new DefaultIdGeneratorFactory( fileSystem )
                {
                    @Override
                    public IdGenerator open( File fileName, int grabSize, IdType idType, LongSupplier highId, long maxId )
                    {
                        IdGenerator idGenerator = super.open( fileName, grabSize, idType, highId, maxId );
                        if ( idType != IdType.LABEL_TOKEN )
                        {
                            return idGenerator;
                        }
                        return new IdGenerator.Delegate( idGenerator )
                        {
                            @Override
                            public long nextId()
                            {
                                throw new UnderlyingStorageException( "Id capacity exceeded" );
                            }
                        };
                    }
                } ).build();
    }
}