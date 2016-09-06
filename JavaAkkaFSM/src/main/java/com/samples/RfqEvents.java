package com.samples;

import java.io.Serializable;

/**
 * Created by richard on 9/2/2016.
 */
public class RfqEvents {

    public static enum BidOffer implements Serializable {
        Bid,
        Offer,
        None
    }

    public interface RequestEvent extends Serializable {} ;

    public static final class SubmitRequest implements RequestEvent {
        public String isin;
        public BidOffer bidoffer;

        public SubmitRequest(String isin, BidOffer bidoffer) {
            this.isin = isin;
            this.bidoffer = bidoffer;
        }
    }

    public static final class DealerAccept implements RequestEvent, Serializable {
        public double price;
        public int wiretime;
        public DealerAccept(double price, int wiretime) {
            this.price = price;
            this.wiretime = wiretime;
        }
    };

    public static final class DealerCounter implements RequestEvent, Serializable {
        public double price;
        public DealerCounter(Double price) {
            this.price = price;
        }
    };

    public static enum DealerReject implements RequestEvent, Serializable { EVENT };
    public static enum CustomerAccept implements RequestEvent, Serializable { EVENT };
    public static enum CustomerReject implements RequestEvent, Serializable { EVENT };
    public static enum BOOM implements RequestEvent, Serializable { EVENT };

}
