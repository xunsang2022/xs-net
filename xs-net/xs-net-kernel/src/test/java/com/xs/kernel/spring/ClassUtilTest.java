package com.xs.kernel.actor;

import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import com.xs.kernel.spring.ClassUtil;
import junit.framework.TestCase;

public class ClassUtilTest extends TestCase {

    public void testResolvesMessageTypeThroughIntermediateGenericParent() {
        assertEquals(TestMessage.class, ClassUtil.findMessageInterface(ConcreteActor.class));
    }

    private interface TestMessage {
    }

    private abstract static class IntermediateActor<M> extends AbstractBehavior<M> {
        protected IntermediateActor(ActorContext<M> context) {
            super(context);
        }
    }

    private static final class ConcreteActor extends IntermediateActor<TestMessage> {
        private ConcreteActor(ActorContext<TestMessage> context) {
            super(context);
        }

        @Override
        public org.apache.pekko.actor.typed.javadsl.Receive<TestMessage> createReceive() {
            return newReceiveBuilder().build();
        }
    }
}
