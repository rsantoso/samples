package com.samples;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.persistence.fsm.AbstractPersistentFSM;
import akka.persistence.fsm.PersistentFSM;
import org.scalatest.concurrent.SleepHelper;
import scala.concurrent.duration.Duration;
import scala.runtime.BoxedUnit;

import java.io.Serializable;
import java.util.UUID;

import static com.samples.Rfq.RequestState.*;
import static com.samples.RfqEvents.*;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Created by richard on 9/2/2016.
 */
public class Rfq {

    // states an RFQ can be in:
    enum RequestState implements PersistentFSM.FSMState {
        New("New"),
        Order("Order"),
        QuoteFirm("Quote Firm"),
        QuoteSubject("Quote Subject"),
        QuoteSubjectAccepted("Quote Subject Accepted"),
        Done("Done"),
        Cancelled("Cancelled");

        private final String stateIdentifier;
        RequestState(String stateIdentifier) {
            this.stateIdentifier = stateIdentifier;
        }

        @Override
        public String identifier() {
            return stateIdentifier;
        }
    }


    public static final class RequestData implements Serializable {
        public final String isin;
        public final BidOffer bidOrOffer;
        public final double price;
        public RequestData(String isin, BidOffer bidOrOffer, double price){
            this.isin = isin;
            this.bidOrOffer = bidOrOffer;
            this.price = price;
        }
    }

    public static class Request extends AbstractPersistentFSM<RequestState, RequestData, RequestEvent> {

        final private String persistenceId;
        private boolean printApplyEvents = false;

        @Override
        public Class<RequestEvent> domainEventClass() {
            return RequestEvent.class;
        }

        @Override
        public String persistenceId() {
            return persistenceId;
        }

        public static Props props(String persistenceId) {
            return Props.create(Request.class, persistenceId);
        }

        /**
         * Override this handler to define the action on Domain Event during recovery
         *
         * @param event       domain event to apply
         * @param currentData state data of the previous state
         */
        //#customer-apply-event
        @Override
        public RequestData applyEvent(RequestEvent event, RequestData currentData) {
            if (event instanceof SubmitRequest) {
                SubmitRequest evt = (SubmitRequest) event;
                return new RequestData(evt.isin, evt.bidoffer, Double.NaN);
            } else if (event instanceof DealerAccept) {
                DealerAccept evt = (DealerAccept) event;
                return new RequestData(currentData.isin, currentData.bidOrOffer, evt.price);
            } else if (event instanceof DealerCounter) {
                DealerCounter evt = (DealerCounter) event;
                return new RequestData(currentData.isin, currentData.bidOrOffer, evt.price);
            }
            throw new RuntimeException("Unhandled event");
        }

        public Request(String persistenceId)
        {
            this.persistenceId = persistenceId;

            // A request begins its existence as New with a blank isin
            startWith(New, new RequestData("", BidOffer.None, Double.NaN));


            // when a submit request comes in, set to to the Order state
            // with initialising
            when(New,
                    matchEvent(SubmitRequest.class, (submitRequest, data) -> {
                        log("Client has submitted a request, isin=%s, type=%s", submitRequest.isin, submitRequest.bidoffer);
                        return goTo(Order).applying(submitRequest);
                    }));

            // when the order is accepted by the dealer, go into quote stage
            when(Order,
                    matchEvent(DealerAccept.class, (dealerAccept, data) -> {
                        log("Dealer has accepted, isin=%s, price=%f, wiretime=%d seconds", data.isin, dealerAccept.price, dealerAccept.wiretime );
                        return goTo(QuoteFirm).forMax(Duration.create(dealerAccept.wiretime, SECONDS));
                    }));

            // dealer rejected
            when(Order,
                    matchEventEquals(DealerReject.EVENT, (dealerReject, data) -> {
                        log("Dealer has rejected, isin=%s", data.isin);
                        return goTo(Cancelled);
                    }));

            // wiretime has expired waiting for client, go into next state
            when(QuoteFirm,
                    matchEventEquals(StateTimeout(), (event, data) -> {
                        log("Wiretime has expired, isin=%s", data.isin);
                        return goTo(QuoteSubject);
                    }));

            // client has accepted a firm quote, deal is done
            when(QuoteFirm,
                    matchEventEquals(CustomerAccept.EVENT, (event, data) -> {
                        log("Client accepted - deal done, isin=%s, price=%f", data.isin, data.price);
                        return goTo(Done);
                    }));

            when(QuoteFirm,
                    matchEventEquals(BOOM.EVENT, (event, data) -> {
                        log("Trigger exception..... !", data.isin);
                        throw new RuntimeException("BOOM !");
                    }));

            // client has accepted but wiretime expired, dealer can counter
            when(QuoteSubject,
                    matchEventEquals(CustomerAccept.EVENT, (event, data) -> {
                        log("Client accepted - deal done, isin=%s, price=%f", data.isin, data.price);
                        return goTo(QuoteSubjectAccepted);
                    }));

            // dealer has countered
            when(QuoteSubjectAccepted,
                    matchEvent(DealerCounter.class, (event, data) -> {
                        log("Dealer countered, isin=%s, price=%f", data.isin, event.price);
                        return goTo(QuoteSubject).applying(event);
                    }));

            // dealer rejected
            when(QuoteSubjectAccepted,
                    matchEventEquals(DealerReject.EVENT,
                            (event, data) -> { return handleDealerRejected(this, event, data); } ));


            // handle any system events
            when(QuoteFirm, matchAnyEvent((event, data) -> { return systemEvent(this, event, data); } ));
            when(QuoteSubject, matchAnyEvent((event, data) -> { return systemEvent(this, event, data); } ));
            when(QuoteSubjectAccepted, matchAnyEvent((event, data) -> { return systemEvent(this, event, data); } ));
            when(Done, matchAnyEvent((event, data) -> { return systemEvent(this, event, data); } ));


            // initialise the rfq
            initialize();

        }

        private void log(String msg, Object ...args) {
            System.out.printf("Current state=%s --- ", this.stateName());
            System.out.printf(msg+"\n", args);
        }

        private static State handleDealerRejected(AbstractPersistentFSM rfq, Object event, RequestData data) {
            System.out.printf("Dealer rejected, isin=%s\n", data.isin);
            rfq.saveStateSnapshot();
            return rfq.goTo(Cancelled);
        }

        private static State systemEvent(AbstractPersistentFSM rfq, Object event, RequestData data) {
            boolean printMsg= true;
            if (printMsg) {
                System.out.printf("System event, %s\n", event);
            }
            return rfq.stay();
        }

        private BoxedUnit currentState(RequestData data) {
            log("Current state=%s", this.stateName());
            return BoxedUnit.UNIT;
        }

    }

    private static String generateId() {
        return UUID.randomUUID().toString();
    }

    public static void main(String[] args) {
        ActorSystem system = ActorSystem.create();

        System.out.println("TEST 1...");
        ActorRef fsmRef = system.actorOf(Rfq.Request.props("Request1"));
        fsmRef.tell(new RfqEvents.SubmitRequest("XS12345", BidOffer.Bid), ActorRef.noSender());
        fsmRef.tell(new RfqEvents.DealerAccept(101.20, 3), ActorRef.noSender());
        fsmRef.tell(CustomerAccept.EVENT, ActorRef.noSender());

        SleepHelper.sleep(500);
        System.out.println("\nTEST 2...wiretime expires");
        fsmRef = system.actorOf(Rfq.Request.props("Request2"));
        fsmRef.tell(new RfqEvents.SubmitRequest("XS67890", BidOffer.Bid), ActorRef.noSender());
        fsmRef.tell(new RfqEvents.DealerAccept(101.20, 3), ActorRef.noSender());
        SleepHelper.sleep(4000);
        fsmRef.tell(CustomerAccept.EVENT, ActorRef.noSender());
        fsmRef.tell(new RfqEvents.DealerCounter(99.20), ActorRef.noSender());
        fsmRef.tell(CustomerAccept.EVENT, ActorRef.noSender());

        SleepHelper.sleep(3000);
        System.out.println("\nTEST 3...exception occurs");
        fsmRef = system.actorOf(Rfq.Request.props("Request3"));
        fsmRef.tell(new RfqEvents.SubmitRequest("XS67890", BidOffer.Bid), ActorRef.noSender());
        fsmRef.tell(new RfqEvents.DealerAccept(101.20, 3), ActorRef.noSender());
        fsmRef.tell(BOOM.EVENT, ActorRef.noSender());
        SleepHelper.sleep(3000);
        fsmRef.tell(CustomerAccept.EVENT, ActorRef.noSender());


    }

}
