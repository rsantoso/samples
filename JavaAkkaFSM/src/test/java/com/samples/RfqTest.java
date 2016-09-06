package com.samples;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.japi.Option;
import akka.persistence.fsm.PersistentFSM;
import akka.testkit.JavaTestKit;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import akka.testkit.TestProbe;
import org.junit.ClassRule;

import java.util.UUID;

import static org.junit.Assert.assertEquals;

/**
 * Created by richard on 9/5/2016.
 */
public class RfqTest extends JUnitSuite {

    private static Option<String> none = Option.none();

//    @ClassRule
//    public static AkkaJUnitActorSystemResource actorSystemResource =
//            new AkkaJUnitActorSystemResource("PersistentFSMJavaTest", PersistenceSpec.config(
//                    "leveldb", "AbstractPersistentFSMTest", "off", none.asScala()));
//
//    private final ActorSystem system = actorSystemResource.getSystem();
    private static final ActorSystem system = ActorSystem.create();

    //Dummy report actor, for tests that don't need it
    private final ActorRef dummyReportActorRef = new TestProbe(system).ref();

    private static String generateId() {
        return UUID.randomUUID().toString();
    }

    private static <State, From extends State, To extends State> void assertTransition(PersistentFSM.Transition transition, ActorRef ref, From from, To to) {
        assertEquals(ref, transition.fsmRef());
        assertEquals(from, transition.from());
        assertEquals(to, transition.to());
    }

    @Test
    public void fsmTimeoutTest() throws Exception {
        new JavaTestKit(system) {{
            String persistenceId = generateId();
            ActorRef fsmRef = system.actorOf(Rfq.Request.props(persistenceId));

            watch(fsmRef);
            fsmRef.tell(new PersistentFSM.SubscribeTransitionCallBack(getRef()), getRef());

            fsmRef.tell(new RfqEvents.SubmitRequest("XS12345", RfqEvents.BidOffer.Bid), getRef());

            PersistentFSM.CurrentState currentState = expectMsgClass(akka.persistence.fsm.PersistentFSM.CurrentState.class);
            assertEquals(currentState.state(), Rfq.RequestState.New);

            PersistentFSM.Transition stateTransition = expectMsgClass(PersistentFSM.Transition.class);
            assertTransition(stateTransition, fsmRef, Rfq.RequestState.New, Rfq.RequestState.Order);

        }};
    }



}
