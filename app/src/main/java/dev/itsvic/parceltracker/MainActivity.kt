package dev.itsvic.parceltracker

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.room.Room
import dev.itsvic.parceltracker.api.ParcelHistoryItem
import dev.itsvic.parceltracker.api.Status
import dev.itsvic.parceltracker.api.getParcel
import dev.itsvic.parceltracker.api.Parcel as APIParcel
import dev.itsvic.parceltracker.db.AppDatabase
import dev.itsvic.parceltracker.ui.theme.ParcelTrackerTheme
import dev.itsvic.parceltracker.ui.views.AddParcelView
import dev.itsvic.parceltracker.ui.views.HomeView
import dev.itsvic.parceltracker.ui.views.ParcelView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import okio.IOException
import java.time.LocalDateTime

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "parcel-tracker")
            .build()

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ParcelTrackerTheme {
                Box(modifier = Modifier.background(color = MaterialTheme.colorScheme.background)) {
                    ParcelAppNavigation(db)
                }
            }
        }
    }
}

@Serializable
object HomePage

@Serializable
data class ParcelPage(val parcelDbId: Int)

@Serializable
object AddParcelPage

@Composable
fun ParcelAppNavigation(db: AppDatabase) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    val animDuration = 300

    NavHost(
        navController = navController,
        startDestination = HomePage,
        enterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(animDuration),
                initialOffset = { it / 4 }
            ) + fadeIn(tween(animDuration))
        },
        exitTransition = {
            fadeOut(tween(animDuration)) + scaleOut(tween(500), 0.9f)
        },
        popEnterTransition = {
            fadeIn(tween(animDuration)) + scaleIn(tween(500), 0.9f)
        },
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(animDuration),
                targetOffset = { -it / 4 }
            ) + fadeOut(tween(animDuration))
        },
    ) {
        composable<HomePage> {
            val parcels = db.parcelDao().getAll().collectAsState(initial = emptyList())

            HomeView(
                parcels = parcels.value,
                onNavigateToAddParcel = { navController.navigate(route = AddParcelPage) },
                onNavigateToParcel = { navController.navigate(route = ParcelPage(it.id)) },
            )
        }
        composable<ParcelPage> { backStackEntry ->
            val route: ParcelPage = backStackEntry.toRoute()
            val parcelDb = db.parcelDao().getById(route.parcelDbId).collectAsState(null)
            var apiParcel: APIParcel? by remember { mutableStateOf(null) }
            val context = LocalContext.current

            LaunchedEffect(parcelDb.value) {
                if (parcelDb.value != null) {
                    launch(Dispatchers.IO) {
                        try {
                            apiParcel = getParcel(
                                parcelDb.value!!.parcelId,
                                parcelDb.value!!.postalCode,
                                parcelDb.value!!.service
                            )
                        } catch (e: IOException) {
                            Log.w("MainActivity", "Failed fetch: $e")
                            apiParcel = APIParcel(
                                parcelDb.value!!.parcelId,
                                listOf(ParcelHistoryItem(
                                    context.getString(R.string.network_failure_detail),
                                    LocalDateTime.now(),
                                    ""
                                )),
                                Status.NetworkFailure
                            )
                        }
                    }
                }
            }

            if (apiParcel == null)
                Box(
                    modifier = Modifier
                        .background(color = MaterialTheme.colorScheme.background)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            else
                ParcelView(
                    apiParcel!!,
                    parcelDb.value!!.humanName,
                    parcelDb.value!!.service,
                    onBackPressed = { navController.popBackStack() }
                )
        }
        composable<AddParcelPage> {
            var addFinished by remember { mutableStateOf(Pair(false, 0)) }

            AddParcelView(
                onBackPressed = { navController.popBackStack() },
                onCompleted = {
                    scope.launch(Dispatchers.IO) {
                        val id = db.parcelDao().insert(it)
                        addFinished = Pair(true, id.toInt())
                    }
                },
            )

            LaunchedEffect(addFinished) {
                if (addFinished.first) {
                    navController.navigate(route = ParcelPage(addFinished.second)) {
                        popUpTo(HomePage)
                    }
                }
            }
        }
    }
}
