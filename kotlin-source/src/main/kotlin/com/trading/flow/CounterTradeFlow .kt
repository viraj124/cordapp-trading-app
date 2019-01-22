package com.trading.flow

import co.paralleluniverse.fibers.Suspendable
import com.trading.contract.TradeContract
import com.trading.contract.TradeContract.Companion.TRADE_CONTRACT_ID
import com.trading.flow.CounterTradeFlow.CounterAcceptor
import com.trading.flow.CounterTradeFlow.CounterInitiator
import com.trading.state.TradeState
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step
import java.sql.ClientInfoStatus
import java.util.*

/**
 * This flow allows two parties (the [Initiator] and the [Acceptor]) to come to an agreement about the Trade encapsulated
 * within an [TradeState].
 *
 * In our simple trading, the [Acceptor] always accepts a valid Trade.
 *
 * These flows have deliberately been implemented by using only the call() method for ease of understanding. In
 * practice we would recommend splitting up the various stages of the flow into sub-routines.
 *
 * All methods called within the [FlowLogic] sub-class need to be annotated with the @Suspendable annotation.
 */
object CounterTradeFlow {
    @InitiatingFlow
    @StartableByRPC
    class CounterInitiator(
                    val sellValue: Int,
                    val sellCurrency: String,
                    val buyValue: Int,
                    val buyCurrency: String,
                    val tradeStatus: String,
                    val tradeId: String,
                    val counterParty: Party,
                    val regulator: Party,
                    val userId: String,
                    val assetCode: String,
                    val orderType: String,
                    val transactionAmount: Int,
                    val transactionFees: Int,
                    val transactionUnits: Int) : FlowLogic<SignedTransaction>() {
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATING_TRANSACTION : Step("Generating transaction based on new Trade.")
            object VERIFYING_TRANSACTION : Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : Step("Signing transaction with our private key.")
            object GATHERING_SIGS : Step("Gathering the counterparty's signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING_TRANSACTION : Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    GENERATING_TRANSACTION,
                    VERIFYING_TRANSACTION,
                    SIGNING_TRANSACTION,
                    GATHERING_SIGS,
                    FINALISING_TRANSACTION
            )
        }

        override val progressTracker = tracker()

        /**
         * The flow logic is encapsulated within the call() method.
         */
        @Suspendable
        override fun call(): SignedTransaction {
            // Obtain a reference to the notary we want to use.
            val notary = serviceHub.networkMapCache.notaryIdentities[0]

            // Stage 1.
            progressTracker.currentStep = GENERATING_TRANSACTION

            // Generate an transaction by taking the current state
            val inputTradeState = serviceHub.vaultService.queryBy<TradeState>().states.singleOrNull{ it.state.data.tradeStatus == "PENDING" && it.state.data.linearId.toString() == tradeId } ?: throw FlowException("No state found in the vault")
            val tradeLinerId= inputTradeState.state.data.linearId.copy(id = inputTradeState.state.data.linearId.id)
            val counterTradeState = TradeState(sellValue,sellCurrency,buyValue,buyCurrency,serviceHub.myInfo.legalIdentities.first(), counterParty,tradeStatus,regulator,userId,assetCode,orderType,transactionAmount,transactionFees,transactionUnits,tradeLinerId)
            val txCommand = Command(TradeContract.Commands.CounterTrade(),  listOf(ourIdentity.owningKey,counterParty.owningKey))
            val txBuilder = TransactionBuilder(notary)
                    .addOutputState(counterTradeState, TRADE_CONTRACT_ID)
                    .addInputState(inputTradeState)
                    .addCommand(txCommand)

            // Stage 2.
            progressTracker.currentStep = VERIFYING_TRANSACTION

            // Verify that the transaction is valid.
            txBuilder.verify(serviceHub)

            // Stage 3.
            progressTracker.currentStep = SIGNING_TRANSACTION

            // Sign the transaction.
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 4.
            progressTracker.currentStep = GATHERING_SIGS

            // Send the state to the counterparty, and receive it back with their signature.
            val otherPartyFlow = initiateFlow(counterParty)
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(otherPartyFlow), GATHERING_SIGS.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            return subFlow(FinalityFlow(fullySignedTx, FINALISING_TRANSACTION.childProgressTracker()))
        }
    }

    @InitiatedBy(CounterInitiator::class)
   open class CounterAcceptor(val otherPartyFlow: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherPartyFlow) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val output = stx.tx.outputs.single().data
                    "This must be an Trade transaction." using (output is TradeState)
                }
            }
            return subFlow(signTransactionFlow)
        }
    }
}
