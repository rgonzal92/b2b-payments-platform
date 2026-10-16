package dev.rgonz.payments.platform.suppliers;

import dev.rgonz.payments.platform.shared.BuyerId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Suppliers and Buyers' Supplier Relationships to them (ADR-0012). */
@Repository
public class Suppliers {

  private final JdbcClient jdbc;

  Suppliers(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * The Buyer's Supplier Relationship to the Supplier the Invoice names, creating the Supplier (by
   * tax ID) and the Relationship if they don't exist yet. Existing ones are reused unchanged,
   * except that a Supplier without an Acceptor ID takes the first one an Invoice carries.
   */
  @Transactional
  public SupplierRelationship relate(BuyerId buyerId, SupplierOnInvoice named) {
    // "On conflict do nothing" makes concurrent first Invoices safe: one insert wins, the others
    // wait for it and then read its row.
    jdbc.sql(
            """
            insert into suppliers.supplier (id, tax_id, name, remittance_address)
            values (:id, :taxId, :name, :remittanceAddress)
            on conflict (tax_id) do nothing
            """)
        .param("id", UUID.randomUUID())
        .param("taxId", named.taxId())
        .param("name", named.name())
        .param("remittanceAddress", named.remittanceAddress())
        .update();
    var supplierId =
        jdbc.sql("select id from suppliers.supplier where tax_id = :taxId")
            .param("taxId", named.taxId())
            .query(UUID.class)
            .single();
    if (named.acceptorId() != null) {
      // Only a Supplier without one takes it, so no later Invoice redirects its Charges. Of two
      // Invoices at once, the second waits for the first's row lock and then finds it set.
      jdbc.sql(
              """
              update suppliers.supplier set acceptor_id = :acceptorId
               where id = :id and acceptor_id is null
              """)
          .param("acceptorId", named.acceptorId())
          .param("id", supplierId)
          .update();
    }
    jdbc.sql(
            """
            insert into suppliers.supplier_relationship (buyer_id, supplier_id, buyer_reference)
            values (:buyerId, :supplierId, :buyerReference)
            on conflict (buyer_id, supplier_id) do nothing
            """)
        .param("buyerId", buyerId.value())
        .param("supplierId", supplierId)
        .param("buyerReference", named.buyerReference())
        .update();
    return find(buyerId, supplierId).orElseThrow();
  }

  /**
   * The Acceptor ID the Network carries on the Supplier's Charges; empty when no Invoice has given
   * the Supplier one, or there is no such Supplier.
   */
  public Optional<String> acceptorId(UUID supplierId) {
    return jdbc.sql("select acceptor_id from suppliers.supplier where id = :id")
        .param("id", supplierId)
        .query(String.class)
        .optional();
  }

  /** Where the Supplier receives Remittance Advice. */
  String remittanceAddressOf(UUID supplierId) {
    return jdbc.sql("select remittance_address from suppliers.supplier where id = :id")
        .param("id", supplierId)
        .query(String.class)
        .single();
  }

  /** The Buyer's Supplier Relationship to this Supplier, if it has one. */
  public Optional<SupplierRelationship> find(BuyerId buyerId, UUID supplierId) {
    return jdbc.sql(
            """
            select s.id, s.tax_id, s.name, s.remittance_address, s.acceptor_id, r.buyer_reference
              from suppliers.supplier_relationship r
              join suppliers.supplier s on s.id = r.supplier_id
             where r.buyer_id = :buyerId
               and r.supplier_id = :supplierId
            """)
        .param("buyerId", buyerId.value())
        .param("supplierId", supplierId)
        .query(
            (row, rowNumber) ->
                new SupplierRelationship(
                    new Supplier(
                        row.getObject("id", UUID.class),
                        row.getString("tax_id"),
                        row.getString("name"),
                        row.getString("remittance_address"),
                        row.getString("acceptor_id")),
                    row.getString("buyer_reference")))
        .optional();
  }
}
