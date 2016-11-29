package org.commonjava.util.partyline.fixture;

import org.junit.rules.TestName;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.junit.runners.model.Statement;
import org.junit.runners.model.TestTimedOutException;

import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.apache.commons.lang.StringUtils.join;
import static org.junit.Assert.fail;

/**
 * Created by jdcasey on 11/28/16.
 */
public final class ThreadDumper
{
    private ThreadDumper()
    {
    }

    public static void dumpThreads()
    {
        StringBuilder sb = new StringBuilder();
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] threadInfos = threadMXBean.getThreadInfo( threadMXBean.getAllThreadIds(), 100 );
        Stream.of( threadInfos ).forEachOrdered( ( ti ) -> {
            if ( sb.length() > 0 )
            {
                sb.append( "\n\n" );
            }

            sb.append( ti.getThreadName() )
              .append( "\n  State: " )
              .append( ti.getThreadState() )
              .append( "\n  Lock Info: " )
              .append( ti.getLockInfo() )
              .append( "\n  Monitors:" );

            MonitorInfo[] monitors = ti.getLockedMonitors();
            if ( monitors == null || monitors.length < 1 )
            {
                sb.append( "  -NONE-" );
            }
            else
            {
                sb.append( "\n  - " ).append( join( monitors, "\n  - " ) );
            }

            sb.append( "\n  Trace:\n    " ).append( join( ti.getStackTrace(), "\n    " ) );

        } );

        System.out.println( sb );
    }

    public static TestRule timeoutRule( int timeout, TimeUnit units )
    {
        return ( base, description ) -> new Statement()
        {
            public void evaluate()
                    throws Throwable
            {
                System.out.printf( "Setting up timeout: %d %s to wrap: %s\n", timeout, units, base );
                AtomicReference<Throwable> error = new AtomicReference<>();
                CountDownLatch latch = new CountDownLatch( 1 );
                FutureTask<Void> task = new FutureTask<>( () -> {
                    try
                    {
                        latch.countDown();
                        base.evaluate();
                    }
                    catch ( Throwable t )
                    {
                        error.set( t );
                    }

                    return null;
                } );

                ThreadGroup tg = new ThreadGroup( "Test Timeout Group" );
                Thread t = new Thread( tg, task, "Test Timeout Thread" );
                t.setDaemon( true );
                t.start();

                try
                {
                    System.out.println("Waiting for test to start.");
                    latch.await();
                }
                catch ( InterruptedException e )
                {
                    error.set( e );
                }

                if ( error.get() == null )
                {
                    try
                    {
                        System.out.println( "Waiting for test to complete (or timeout)" );
                        task.get( timeout, units );
                    }
                    catch ( InterruptedException e )
                    {
                        error.set( e );
                    }
                    catch ( ExecutionException e )
                    {
                        error.set( e.getCause() );
                    }
                    catch ( TimeoutException e )
                    {
                        System.out.printf( "Test timeout %d %s expired!\n", timeout, units.name() );
                        dumpThreads();
                        fail( String.format( "Test timed out after %d %s", timeout, units ) );
                    }
                }

                Throwable throwable = error.get();
                if ( throwable != null )
                {
                    throw throwable;
                }
            }
        };
    }
}
