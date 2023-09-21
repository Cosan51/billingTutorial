package alcorp.com.billingtutorial

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.LifecycleObserver
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.consumePurchase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.min


/**
 * The BillingManager implements all billing functionality.
 * Purchases can happen while in the app or at any time while out of the app, so the
 * BillingManager has to account for that.
 *
 * Product ID can have an individual state, all product ID have an associated StateFlow
 * to allow their state to be observed.
 *
 * This BillingManager knows nothing about the application; all necessary information is either
 * passed into the constructor, exported as observable Flows, or exported through callbacks.
 * This code can be reused in a variety of apps.
 *
 * Beginning a purchase flow involves passing an Activity into the Billing Library, but we merely
 * pass it along to the API.
 *
 * This manager has a few automatic features:
 * 1) It checks for a valid signature on all purchases before attempting to acknowledge them.
 * 2) It automatically acknowledges all known Product IDs for non-consumables, and doesn't set the state
 * to purchased until the acknowledgement is complete.
 * 3) The manager will automatically consume productIDs that are set in knownAutoConsumeProductIDs. As
 * ProductDetails are consumed, a Flow will emit.
 * 4) If the BillingService is disconnected, it will attempt to reconnect with exponential
 * fallback.
 *
 * This manager attempts to keep billing library specific knowledge confined to this file;
 * The only thing that clients of the BillingManager need to know are the productIDs used by their
 * application.
 *
 * The BillingClient needs access to the Application context in order to bind the remote billing
 * service.
 *
 * The BillingManager can also act as a LifecycleObserver for an Activity; this allows it to
 * refresh purchases during onResume.
 */

private const val RECONNECT_TIMER_START_MILLISECONDS = 1L * 1000L
private const val RECONNECT_TIMER_MAX_TIME_MILLISECONDS = 1000L * 60L * 15L // 15 minutes
private const val PRODUCT_DETAILS_REQUERY_TIME = 1000L * 60L * 60L * 4L // 4 hours

class BillingManager private constructor(
    application: Application,
    private val defaultScope: CoroutineScope,
    knownInAppProductIDs: Array<String>?,
    knownSubscriptionProductIDs: Array<String>?,
    autoConsumeProductIDs: Array<String>?
) :
    LifecycleObserver, PurchasesUpdatedListener, BillingClientStateListener {

    // Billing client, connection, cached data
    private val billingClient: BillingClient

    // known ProductIDs (used to query ProductDetails data and validate responses)
    private val knownInAppProductIDs: List<String>?
    private val knownSubscriptionProductIDs: List<String>?

    // ProductIDs to auto-consume
    private val knownAutoConsumeProductIDs: MutableSet<String>

    // how long before the data source tries to reconnect to Google play
    private var reconnectMilliseconds = RECONNECT_TIMER_START_MILLISECONDS

    // when was the last successful ProductDetailsResponse?
    private var productDetailsResponseTime = -PRODUCT_DETAILS_REQUERY_TIME

    private enum class ProductState {
        PRD_STATE_UNPURCHASED,
        PRD_STATE_PENDING,
        PRD_STATE_PURCHASED,
        PRD_STATE_PURCHASED_AND_ACKNOWLEDGED
    }

    // Flows that are mostly maintained so they can be transformed into observables.
    private val productStateMap: MutableMap<String, MutableStateFlow<ProductState>> = HashMap()
    private val productDetailsMap: MutableMap<String, MutableStateFlow<ProductDetails?>> = HashMap()

    // Observables that are used to communicate state.
    private val purchaseConsumptionInProcess: MutableSet<Purchase> = HashSet()
    private val newPurchaseFlow = MutableSharedFlow<List<String>>(extraBufferCapacity = 1)
    private val purchaseConsumedFlow = MutableSharedFlow<List<String>>()
    private val billingFlowInProcess = MutableStateFlow(false)

    override fun onBillingSetupFinished(billingResult: BillingResult) {
        val responseCode = billingResult.responseCode
        val debugMessage = billingResult.debugMessage
        Log.d(TAG, "onBillingSetupFinished: $responseCode $debugMessage")
        when (responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                // The billing client is ready. You can query purchases here.
                // This doesn't mean that your app is set up correctly in the console -- it just
                // means that you have a connection to the Billing service.
                reconnectMilliseconds = RECONNECT_TIMER_START_MILLISECONDS
                defaultScope.launch {
                    queryProductDetailsAsync()
                    refreshPurchases()
                }
            }

            else -> retryBillingServiceConnectionWithExponentialBackoff()
        }
    }

    /**
     * This is a pretty unusual occurrence. It happens primarily if the Google Play Store
     * self-upgrades or is force closed.
     */
    override fun onBillingServiceDisconnected() {
        retryBillingServiceConnectionWithExponentialBackoff()
    }

    /**
     * Retries the billing service connection with exponential backoff, maxing out at the time
     * specified by RECONNECT_TIMER_MAX_TIME_MILLISECONDS.
     */
    private fun retryBillingServiceConnectionWithExponentialBackoff() {
        handler.postDelayed(
            { billingClient.startConnection(this@BillingManager) },
            reconnectMilliseconds
        )
        reconnectMilliseconds = min(
            reconnectMilliseconds * 2,
            RECONNECT_TIMER_MAX_TIME_MILLISECONDS
        )
    }

    /**
     * Called by initializeFlows to create the various Flow objects we're planning to emit.
     * @param productList a List<String> of ProductIDs representing purchases and subscriptions.
    </String> */
    private fun addProductFlows(productList: List<String>?) {
        for (product in productList!!) {
            val productState = MutableStateFlow(ProductState.PRD_STATE_UNPURCHASED)
            val details = MutableStateFlow<ProductDetails?>(null)
            details.subscriptionCount.map { count -> count > 0 } // map count into active/inactive flag
                .distinctUntilChanged() // only react to true<->false changes
                .onEach { isActive -> // configure an action
                    if (isActive && (SystemClock.elapsedRealtime() - productDetailsResponseTime > PRODUCT_DETAILS_REQUERY_TIME)) {
                        productDetailsResponseTime = SystemClock.elapsedRealtime()
                        Log.v(TAG, "ProductID not fresh, requiring")
                        queryProductDetailsAsync()
                    }
                }
                .launchIn(defaultScope) // launch it
            productStateMap[product] = productState
            productDetailsMap[product] = details
        }
    }

    /**
     * Creates a Flow object for every known ProductDetails so the state and ProductDetails details can be observed
     * in other layers. The repository is responsible for mapping this data in ways that are more
     * useful for the application.
     */
    private fun initializeFlows() {
        addProductFlows(knownInAppProductIDs)
        addProductFlows(knownSubscriptionProductIDs)
    }

    fun getNewPurchases() = newPurchaseFlow.asSharedFlow()

    /**
     * This is a flow that is used to observe consumed purchases.
     * @return Flow that contains productIDs of the consumed purchases.
     */
    fun getConsumedPurchases() = purchaseConsumedFlow.asSharedFlow()

    /**
     * Returns whether or not the user has purchased a Product. It does this by returning
     * a Flow that returns true if the Product is in the PURCHASED state and
     * the Purchase has been acknowledged.
     * @return a Flow that observes the Product purchase state
     */
    fun isPurchased(productID: String): Flow<Boolean> {
        val productStateFLow = productStateMap[productID]!!

        return productStateFLow.map { productState -> productState == ProductState.PRD_STATE_PURCHASED_AND_ACKNOWLEDGED }
    }

    /**
     * Returns whether or not the user can purchase a productID. It does this by returning
     * a Flow combine transformation that returns true if the productID is in the UNSPECIFIED state, as
     * well as if we have ProductDetails for the Product. (productIDs cannot be purchased without valid
     * ProductDetails.)
     * @return a Flow that observes the productIDs purchase state
     */
    fun canPurchase(productID: String): Flow<Boolean> {
        val productDetailsFlow = productDetailsMap[productID]!!
        val productStateFlow = productStateMap[productID]!!

        return productStateFlow.combine(productDetailsFlow) { productState, productDetails ->
            productState == ProductState.PRD_STATE_UNPURCHASED && productDetails != null
        }
    }

    /**
     * The title of our productID from ProductDetails.
     * @param productID to get the title from
     * @return title of the requested productID as an observable Flow<String>
    </String> */
    fun getProductTitle(productID: String): Flow<String> {
        val productDetailsFlow = productDetailsMap[productID]!!
        return productDetailsFlow.mapNotNull { productDetails ->
            productDetails?.title
        }
    }

    fun getProductPrice(productID: String): Flow<String> {
        val productDetailsFlow = productDetailsMap[productID]!!
        return productDetailsFlow.mapNotNull { productDetails ->
            productDetails?.oneTimePurchaseOfferDetails?.formattedPrice
        }
    }

    fun getProductDescription(productID: String): Flow<String> {
        val productDetailsFlow = productDetailsMap[productID]!!
        return productDetailsFlow.mapNotNull { productDetails ->
            productDetails?.description
        }
    }

    /**
     * Receives the result from [.queryProductDetailsAsync]}.
     *
     * Store the ProductDetails and post them in the [.productDetailsMap]. This allows other
     * parts of the app to use the [ProductDetails] to show ProductDetail information and make purchases.
     */
    private fun onProductDetailsResponse(
        billingResult: BillingResult,
        productDetailsList: List<ProductDetails>?
    ) {
        val responseCode = billingResult.responseCode
        val debugMessage = billingResult.debugMessage
        when (responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                Log.i(TAG, "onProductDetailsResponse: $responseCode $debugMessage")
                if (productDetailsList.isNullOrEmpty()) {
                    Log.e(
                        TAG,
                        "onProductDetailsResponse: " +
                                "Found null or empty ProductDetails. " +
                                "Check to see if the productID you requested are correctly published " +
                                "in the Google Play Console."
                    )
                } else {
                    for (productDetails in productDetailsList) {
                        val productId = productDetails.productId
                        val detailsMutableFlow = productDetailsMap[productId]
                        detailsMutableFlow?.tryEmit(productDetails) ?: Log.e(TAG, "Unknown productID: $productId")
                    }
                }
            }

            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
            BillingClient.BillingResponseCode.DEVELOPER_ERROR,
            BillingClient.BillingResponseCode.ERROR -> {
                Log.e(TAG, "onProductDetailsResponse: $responseCode $debugMessage")
            }

            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.i(TAG, "onProductDetailsResponse: $responseCode $debugMessage")
            }

            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED,
            BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> {
                Log.wtf(TAG, "onProductDetailsResponse: $responseCode $debugMessage")
            }

            else -> Log.wtf(TAG, "onProductDetailsResponse: $responseCode $debugMessage")
        }

        productDetailsResponseTime = if (responseCode == BillingClient.BillingResponseCode.OK) {
            SystemClock.elapsedRealtime()
        } else {
            -PRODUCT_DETAILS_REQUERY_TIME
        }
    }

    /**
     * Calls the billing client functions to query productID details for both the inApp and subscription
     * Product IDs. Product details are useful for displaying item names and price lists to the user, and are
     * required to make a purchase.
     */
    private fun queryProductDetailsAsync() {

        if (!knownInAppProductIDs.isNullOrEmpty()) {

            val params = QueryProductDetailsParams.newBuilder()
            val productList: MutableList<QueryProductDetailsParams.Product> = arrayListOf()
            for (product in knownInAppProductIDs) {
                productList.add(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(product)
                        .setProductType(ProductType.INAPP)
                        .build()
                )
            }

            params.setProductList(productList).let { productDetailsParams ->
                billingClient.queryProductDetailsAsync(productDetailsParams.build()) { billingResult, productDetailsList ->
                    onProductDetailsResponse(billingResult, productDetailsList)
                }
            }
        }
        if (!knownSubscriptionProductIDs.isNullOrEmpty()) {

            val params = QueryProductDetailsParams.newBuilder()
            val productList: MutableList<QueryProductDetailsParams.Product> = arrayListOf()

            for (productId in knownSubscriptionProductIDs) {
                productList.add(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            }

            params.setProductList(productList).let { productDetailsParams ->
                billingClient.queryProductDetailsAsync(productDetailsParams.build()) { billingResult, productDetailsList ->
                    onProductDetailsResponse(billingResult, productDetailsList)
                }
            }
        }
    }

    suspend fun refreshPurchases() {
        Log.d(TAG, "Refreshing purchases.")

        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(ProductType.INAPP)
                .build()
        ) { billingResult, purchasesResult ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.e(TAG, "Problem getting purchases: " + billingResult.debugMessage)
            } else {
                processPurchaseList(purchasesResult, knownInAppProductIDs)
            }
        }

        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(ProductType.SUBS)
                .build()
        ) { billingResult, purchasesResult ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.e(TAG, "Problem getting subscriptions: " + billingResult.debugMessage)
            } else {
                processPurchaseList(purchasesResult, knownSubscriptionProductIDs)
            }
        }


        Log.d(TAG, "Refreshing purchases finished.")
    }

    /**
     * Used internally to get purchases from a requested set of ProductDetails. This is particularly
     * important when changing subscriptions, as onPurchasesUpdated won't update the purchase state
     * of a subscription that has been upgraded from.
     *
     * @param aProductIDs array of String of productID to get purchase information for
     * @param productType product type, inApp or subscription, to get purchase information for.
     * @return purchases
     */
    private suspend fun getPurchases(
        aProductIDs: Array<String>,
        productType: String
    ): List<Purchase> = suspendCoroutine { continuation ->
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(productType)
                .build()
        ) { billingResult, purchasesList ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.e(TAG, "Problem getting purchases: " + billingResult.debugMessage)
                continuation.resume(emptyList()) // Return an empty list in case of an error
            } else {
                val returnPurchasesList = mutableListOf<Purchase>()
                for (purchase in purchasesList) {
                    for (productID in aProductIDs) {
                        for (purchaseID in purchase.products) {
                            if (purchaseID == productID) {
                                returnPurchasesList.add(purchase)
                            }
                        }
                    }
                }
                continuation.resume(returnPurchasesList)
            }
        }
    }


    /**
     * Consumes an in-app purchase. Interested listeners can watch the purchaseConsumed LiveEvent.
     * To make things easy, you can send in a list of ProductIDs that are auto-consumed by the
     * BillingManager.
     */
    suspend fun consumeInAppPurchase(productID: String): Boolean = suspendCoroutine { continuation ->
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP)
                .build()
        ) { billingResult, purchasesList ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.e(TAG, "Problem getting purchases: " + billingResult.debugMessage)
                continuation.resume(false) // Return false to indicate an error
            } else {
                var productFound = false
                for (purchase in purchasesList) {
                    for (purchaseID in purchase.products) {
                        if (purchaseID == productID) {
                            productFound = true
                            defaultScope.launch {
                                consumePurchase(purchase)
                            }
                            break
                        }
                    }
                    if (productFound) break
                }
                if (productFound) {
                    continuation.resume(true) // Return true to indicate success
                } else {
                    Log.e(TAG, "Unable to consume Product: $productID productID not found.")
                    continuation.resume(false) // Return false to indicate an error
                }
            }
        }
    }


    /**
     * Calling this means that we have the most up-to-date information for a productID in a purchase
     * object. This uses the purchase state (Pending, Unspecified, Purchased) along with the
     * acknowledged state.
     * @param purchase an up-to-date object to set the state for the productID
     */
    private fun setProductStateFromPurchase(purchase: Purchase) {
        for (purchaseID in purchase.products) {
            val productStateFlow = productStateMap[purchaseID]
            if (null == productStateFlow) {
                Log.e(
                    TAG,
                    "Unknown purchaseID " + purchaseID + ". Check to make " +
                            "sure purchaseID matches ProductDetailsID in the Play developer console."
                )
            } else {
                when (purchase.purchaseState) {
                    Purchase.PurchaseState.PENDING -> productStateFlow.tryEmit(ProductState.PRD_STATE_PENDING)
                    Purchase.PurchaseState.UNSPECIFIED_STATE -> productStateFlow.tryEmit(ProductState.PRD_STATE_UNPURCHASED)
                    Purchase.PurchaseState.PURCHASED -> if (purchase.isAcknowledged) {
                        productStateFlow.tryEmit(ProductState.PRD_STATE_PURCHASED_AND_ACKNOWLEDGED)
                    } else {
                        productStateFlow.tryEmit(ProductState.PRD_STATE_PURCHASED)
                    }

                    else -> Log.e(TAG, "Purchase in unknown state: " + purchase.purchaseState)
                }
            }
        }
    }

    /**
     * Since we (mostly) are getting product states when we actually make a purchase or update
     * purchases, we keep some internal state when we do things like acknowledge or consume.
     * @param purchaseID product ID to change the state of
     * @param newProductState the new state of the productID.
     */
    private fun setProductState(purchaseID: String, newProductState: ProductState) {
        val productStateFlow = productStateMap[purchaseID]
        productStateFlow?.tryEmit(newProductState) ?: Log.e(TAG, "Unknown purchaseID " + purchaseID + ". Check to make " +
                        "sure productID matches Products in the Play developer console."
            )
    }

    /**
     * Goes through each purchase and makes sure that the purchase state is processed and the state
     * is available through Flows. Verifies signature and acknowledges purchases. PURCHASED isn't
     * returned until the purchase is acknowledged.
     *
     * If the purchase token is not acknowledged within 3 days,
     * then Google Play will automatically refund and revoke the purchase.
     * This behavior helps ensure that users are not charged unless the user has successfully
     * received access to the content.
     * This eliminates a category of issues where users complain to developers
     * that they paid for something that the app is not giving to them.
     *
     * If a productIDsToUpdate list is passed-into this method, any purchases not in the list of
     * purchases will have their state set to UNPURCHASED.
     *
     * @param purchases the List of purchases to process.
     * @param productIDsToUpdate a list of productIDs that we want to update the state from --- this allows us
     * to set the state of non-returned productIDs to UNPURCHASED.
     */
    private fun processPurchaseList(purchases: List<Purchase>?, productIDsToUpdate: List<String>?) {
        val updatedProductIDs = HashSet<String>()
        if (null != purchases) {
            for (purchase in purchases) {
                for (productID in purchase.products) {
                    val productStateFlow = productStateMap[productID]
                    if (null == productStateFlow) {
                        Log.e(
                            TAG,
                            "Unknown product ID " + productID + ". Check to make " +
                                    "sure product ID matches product IDs in the Play developer console."
                        )
                        continue
                    }
                    updatedProductIDs.add(productID)
                }
                // Global check to make sure all purchases are signed correctly.
                // This check is best performed on your server.
                val purchaseState = purchase.purchaseState
                if (purchaseState == Purchase.PurchaseState.PURCHASED) {
                    if (!isSignatureValid(purchase)) {
                        Log.e(
                            TAG,
                            "Invalid signature. Check to make sure your " +
                                    "public key is correct."
                        )
                        continue
                    }
                    // only set the purchased state after we've validated the signature.
                    setProductStateFromPurchase(purchase)
                    var isConsumable = false
                    defaultScope.launch {
                        for (purchaseID in purchase.products) {
                            if (knownAutoConsumeProductIDs.contains(purchaseID)) {
                                isConsumable = true
                            } else {
                                if (isConsumable) {
                                    Log.e(
                                        TAG, "Purchase cannot contain a mixture of consumable" +
                                                "and non-consumable items: " + purchase.products.toString()
                                    )
                                    isConsumable = false
                                    break
                                }
                            }
                        }
                        if (isConsumable) {
                            consumePurchase(purchase)
                            newPurchaseFlow.tryEmit(purchase.products)
                        } else if (!purchase.isAcknowledged) {
                            // acknowledge everything --- new purchases are ones not yet acknowledged
                            val billingResult = billingClient.acknowledgePurchase(
                                AcknowledgePurchaseParams.newBuilder()
                                    .setPurchaseToken(purchase.purchaseToken)
                                    .build()
                            )
                            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                                Log.e(
                                    TAG,
                                    "Error acknowledging purchase: ${purchase.products}"
                                )
                            } else {
                                // purchase acknowledged
                                for (purchaseID in purchase.products) {
                                    setProductState(
                                        purchaseID,
                                        ProductState.PRD_STATE_PURCHASED_AND_ACKNOWLEDGED
                                    )
                                }
                            }
                            newPurchaseFlow.tryEmit(purchase.products)
                        }
                    }
                } else {
                    // make sure the state is set
                    setProductStateFromPurchase(purchase)
                }
            }
        } else {
            Log.d(TAG, "Empty purchase list.")
        }
        // Clear purchase state of anything that didn't come with this purchase list if this is
        // part of a refresh.
        if (null != productIDsToUpdate) {
            for (productID in productIDsToUpdate) {
                if (!updatedProductIDs.contains(productID)) {
                    setProductState(productID, ProductState.PRD_STATE_UNPURCHASED)
                }
            }
        }
    }

    /**
     * Internal call only. Assumes that all signature checks have been completed and the purchase
     * is ready to be consumed. If the productID is already being consumed, does nothing.
     * @param purchase purchase to consume
     */
    private suspend fun consumePurchase(purchase: Purchase) {
        // weak check to make sure we're not already consuming the productID
        if (purchaseConsumptionInProcess.contains(purchase)) {
            // already consuming
            return
        }
        purchaseConsumptionInProcess.add(purchase)
        val consumePurchaseResult = billingClient.consumePurchase(
            ConsumeParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
        )

        purchaseConsumptionInProcess.remove(purchase)
        if (consumePurchaseResult.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            Log.d(TAG, "Consumption successful. Emitting ProductID.")
            defaultScope.launch {
                purchaseConsumedFlow.emit(purchase.products)
            }
            // Since we've consumed the purchase
            for (productID in purchase.products) {
                setProductState(productID, ProductState.PRD_STATE_UNPURCHASED)
            }
        } else {
            Log.e(TAG, "Error while consuming: ${consumePurchaseResult.billingResult.debugMessage}")
        }
    }

    /**
     * Launch the billing flow. This will launch an external Activity for a result, so it requires
     * an Activity reference. For subscriptions, it supports upgrading from one Product type to another
     * by passing in productID to be upgraded.
     *
     * @param activity active activity to launch our billing flow from
     * @param productId String (Product ID) to be purchased
     * @param selectedOfferIndex Int the selected offer index
     * @param upgradeProductIDsVarargs productIDs that the subscription can be upgraded from
     * @return true if launch is successful
     */
    fun launchBillingFlow(
        activity: Activity?,
        productId: String,
        selectedOfferIndex: Int,
        vararg upgradeProductIDsVarargs: String
    ) {
        val productDetails = productDetailsMap[productId]?.value
        if (null != productDetails) {

            val offerToken =
                productDetails.subscriptionOfferDetails?.get(selectedOfferIndex)?.offerToken

            val productDetailsParamsList = listOf(
                offerToken?.let {
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .setOfferToken(it)
                        .build()
                }
            )

            val billingFlowParamsBuilder = BillingFlowParams.newBuilder()
            billingFlowParamsBuilder.setProductDetailsParamsList(productDetailsParamsList)

            val upgradeProduct = arrayOf(*upgradeProductIDsVarargs)
            defaultScope.launch {
                val heldSubscriptions = getPurchases(upgradeProduct, ProductType.SUBS)
                when (heldSubscriptions.size) {
                    1 -> {
                        val purchase = heldSubscriptions[0]
                        billingFlowParamsBuilder.setSubscriptionUpdateParams(
                            BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                                .setOldPurchaseToken(purchase.purchaseToken)
                                .build()
                        )
                    }

                    else -> Log.e(
                        TAG,
                        heldSubscriptions.size.toString() + " subscriptions subscribed to. Upgrade not possible."
                    )
                }
                val billingResult = billingClient.launchBillingFlow(
                    activity!!, billingFlowParamsBuilder.build()
                )
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    billingFlowInProcess.emit(true)
                } else {
                    Log.e(TAG, "Billing failed: + " + billingResult.debugMessage)
                }
            }
        } else {
            Log.e(TAG, "ProductDetails not found for: $productId")
        }
    }

    /**
     * Returns a Flow that reports if a billing flow is in process, meaning that
     * launchBillingFlow has returned BillingResponseCode.OK and onPurchasesUpdated hasn't yet
     * been called.
     * @return Flow that indicates the known state of the billing flow.
     */
    fun getBillingFlowInProcess(): Flow<Boolean> {
        return billingFlowInProcess.asStateFlow()
    }

    /**
     * Called by the BillingLibrary when new purchases are detected; typically in response to a
     * launchBillingFlow.
     * @param billingResult result of the purchase flow.
     * @param list of new purchases.
     */
    override fun onPurchasesUpdated(billingResult: BillingResult, list: List<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> if (null != list) {
                processPurchaseList(list, null)
                return
            } else Log.d(TAG, "Null Purchase List Returned from OK response!")

            BillingClient.BillingResponseCode.USER_CANCELED -> Log.i(
                TAG,
                "onPurchasesUpdated: User canceled the purchase"
            )

            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> Log.i(
                TAG,
                "onPurchasesUpdated: The user already owns this item"
            )

            BillingClient.BillingResponseCode.DEVELOPER_ERROR -> Log.e(
                TAG,
                "onPurchasesUpdated: Developer error means that Google Play " +
                        "does not recognize the configuration. If you are just getting started, " +
                        "make sure you have configured the application correctly in the " +
                        "Google Play Console. The product ID must match and the APK you " +
                        "are using must be signed with release keys."
            )

            else -> Log.d(
                TAG,
                "BillingResult [" + billingResult.responseCode + "]: " + billingResult.debugMessage
            )
        }
        defaultScope.launch {
            billingFlowInProcess.emit(false)
        }
    }

    /**
     * Ideally your implementation will comprise a secure server, rendering this check
     * unnecessary. @see [SecurityHelper]
     */
    private fun isSignatureValid(purchase: Purchase): Boolean {
        return SecurityHelper.verifyPurchase(purchase.originalJson, purchase.signature)
    }

    companion object {
        private val TAG = "MyBillingManager:" + BillingManager::class.java.simpleName

        @Volatile
        private var sInstance: BillingManager? = null
        private val handler = Handler(Looper.getMainLooper())

        // Standard boilerplate double check locking pattern for thread-safe singletons.
        @JvmStatic
        fun getInstance(
            application: Application,
            defaultScope: CoroutineScope,
            knownInAppProductIDs: Array<String>?,
            knownSubscriptionProductIDs: Array<String>?,
            autoConsumeProductIDs: Array<String>?
        ) = sInstance ?: synchronized(this) {
            sInstance ?: BillingManager(
                application,
                defaultScope,
                knownInAppProductIDs,
                knownSubscriptionProductIDs,
                autoConsumeProductIDs
            )
                .also { sInstance = it }
        }
    }

    /**
     * Our constructor.  Since we are a singleton, this is only used internally.
     * @param application Android application class.
     * @param knownInAppProductIDs ProductDetailsIDs of in-app purchases the source should know about
     * @param knownSubscriptionProductIDs ProductDetailsIDs of subscriptions the source should know about
     */
    init {
        this.knownInAppProductIDs = if (knownInAppProductIDs == null) {
            ArrayList()
        } else {
            listOf(*knownInAppProductIDs)
        }
        this.knownSubscriptionProductIDs = if (knownSubscriptionProductIDs == null) {
            ArrayList()
        } else {
            listOf(*knownSubscriptionProductIDs)
        }
        knownAutoConsumeProductIDs = HashSet()
        if (autoConsumeProductIDs != null) {
            knownAutoConsumeProductIDs.addAll(listOf(*autoConsumeProductIDs))
        }
        initializeFlows()
        billingClient = BillingClient.newBuilder(application)
            .setListener(this)
            .enablePendingPurchases()
            .build()
        billingClient.startConnection(this)
    }
}
