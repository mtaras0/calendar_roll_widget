package com.example.todaycalendarwidget

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.todaycalendarwidget.ui.theme.TodayCalendarWidgetTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.model.CalendarListEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.ZonedDateTime
// import java.time.format.DateTimeFormatter // Not directly used in MainActivity after UI change

class MainActivity : ComponentActivity() {
    private lateinit var dataStoreManager: PreferencesDataStoreManager
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dataStoreManager = PreferencesDataStoreManager(applicationContext)
        setContent {
            TodayCalendarWidgetTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(dataStoreManager)
                }
            }
        }
    }
}

// Helper to build Calendar service
private fun buildCalendarService(context: android.content.Context, account: GoogleSignInAccount): Calendar {
    val credential = GoogleAccountCredential.usingOAuth2(
        context,
        setOf(Scope("https://www.googleapis.com/auth/calendar.readonly"))
    ).setSelectedAccount(account.account)

    return Calendar.Builder(
        AndroidHttp.newCompatibleTransport(),
        GsonFactory.getDefaultInstance(),
        credential
    )
        .setApplicationName(context.getString(R.string.app_name))
        .build()
}

private suspend fun fetchCalendarList(context: android.content.Context, account: GoogleSignInAccount): List<CalendarListEntry>? {
    return withContext(Dispatchers.IO) {
        try {
            val calendarService = buildCalendarService(context, account)
            val calendarListResult = calendarService.calendarList().list().setFields("items(id,summary,selected)").execute()
            calendarListResult.items
        } catch (e: Exception) {
            throw e
        }
    }
}

private suspend fun fetchEventsForToday(
    context: android.content.Context,
    account: GoogleSignInAccount,
    selectedCalendarIds: Set<String>
): List<MyCalendarEvent> {
    if (selectedCalendarIds.isEmpty()) return emptyList()

    return withContext(Dispatchers.IO) {
        val calendarService = buildCalendarService(context, account)
        val allFetchedEvents = mutableListOf<com.google.api.services.calendar.model.Event>()

        val timeMin = DateTimeUtils.getStartOfTodayDateTime()
        val timeMax = DateTimeUtils.getEndOfTodayDateTime()

        for (calendarId in selectedCalendarIds) {
            try {
                val events = calendarService.events().list(calendarId)
                    .setTimeMin(timeMin)
                    .setTimeMax(timeMax)
                    .setOrderBy("startTime")
                    .setSingleEvents(true)
                    .setFields("items(id,summary,start,end)")
                    .execute()
                allFetchedEvents.addAll(events.items ?: emptyList())
            } catch (e: Exception) {
                // Log individual calendar fetch error if necessary
            }
        }
        allFetchedEvents.sortWith(compareBy { it.start?.dateTime ?: it.start?.date })
        allFetchedEvents.map { event ->
            val startTime = DateTimeUtils.convertGoogleDateTime(event.start)
            val endTime = DateTimeUtils.convertGoogleDateTime(event.end)
            val isAllDay = event.start?.dateTime == null && event.start?.date != null
            MyCalendarEvent(
                id = event.id,
                summary = event.summary,
                startTime = startTime,
                endTime = endTime,
                isAllDay = isAllDay
            )
        }
    }
}

@Composable
fun MainScreen(dataStoreManager: PreferencesDataStoreManager, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hasCalendarPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
    }
    var googleSignInAccount by remember { mutableStateOf<GoogleSignInAccount?>(null) }
    var calendarList by remember { mutableStateOf<List<CalendarListEntry>>(emptyList()) }
    val selectedCalendars = remember { mutableStateMapOf<String, Boolean>() }
    var isLoadingCalendars by remember { mutableStateOf(false) }
    var calendarError by remember { mutableStateOf<String?>(null) }
    var todayEvents by remember { mutableStateOf<List<MyCalendarEvent>>(emptyList()) }
    var isLoadingEvents by remember { mutableStateOf(false) }
    var eventsError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> hasCalendarPermission = isGranted }
    )

    val googleSignInClient: GoogleSignInClient = remember {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope("https://www.googleapis.com/auth/calendar.readonly"))
            .build()
        GoogleSignIn.getClient(context, gso)
    }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            val task: Task<GoogleSignInAccount> = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            handleSignInResult(task, onSuccess = { account -> googleSignInAccount = account }, onFailure = { googleSignInAccount = null })
        }
    )

    fun signIn() = signInLauncher.launch(googleSignInClient.signInIntent)
    fun signOut() = googleSignInClient.signOut().addOnCompleteListener { googleSignInAccount = null }

    remember { // Initial check for signed-in user
        val lastAccount = GoogleSignIn.getLastSignedInAccount(context)
        if (lastAccount != null && lastAccount.grantedScopes.contains(Scope("https://www.googleapis.com/auth/calendar.readonly"))) {
            googleSignInAccount = lastAccount
        } else if (lastAccount != null) {
            googleSignInClient.silentSignIn().addOnCompleteListener { task ->
                handleSignInResult(task, onSuccess = { acc -> googleSignInAccount = acc }, onFailure = { googleSignInAccount = null })
            }
        }
        true
    }

    LaunchedEffect(selectedCalendars) {
        snapshotFlow { selectedCalendars.toMap() }
            .debounce(300)
            .collectLatest { currentSelectionsMap ->
                val selectedIdsSet = currentSelectionsMap.filter { it.value }.keys
                dataStoreManager.saveSelectedCalendarIds(selectedIdsSet)
                if (hasCalendarPermission && googleSignInAccount != null) {
                    if (selectedIdsSet.isNotEmpty()) {
                        isLoadingEvents = true
                        eventsError = null
                        try {
                            val fetchedEvents = fetchEventsForToday(context, googleSignInAccount!!, selectedIdsSet)
                            todayEvents = fetchedEvents
                            eventsError = if (fetchedEvents.isEmpty() && selectedIdsSet.isNotEmpty()) null else eventsError // Clear error if events found or if no specific error for empty
                        } catch (e: IOException) {
                            eventsError = "Network error fetching events: ${e.message?.take(100)}"
                            todayEvents = emptyList()
                        } catch (e: Exception) {
                            eventsError = "Error fetching events: ${e.message?.take(100)}"
                            todayEvents = emptyList()
                        } finally {
                            isLoadingEvents = false
                        }
                    } else {
                        todayEvents = emptyList()
                        eventsError = null
                    }
                }
            }
    }

    LaunchedEffect(googleSignInAccount, hasCalendarPermission) {
        if (hasCalendarPermission && googleSignInAccount != null) {
            coroutineScope.launch {
                dataStoreManager.selectedCalendarIdsFlow.collectLatest { ids ->
                    val newSelections = mutableMapOf<String, Boolean>()
                    ids.forEach { id -> newSelections[id] = true }
                    if (selectedCalendars.entries != newSelections.entries) {
                        selectedCalendars.clear()
                        selectedCalendars.putAll(newSelections)
                    }
                }
            }
            coroutineScope.launch {
                isLoadingCalendars = true
                calendarError = null
                try {
                    val calendars = fetchCalendarList(context, googleSignInAccount!!)
                    calendarList = calendars ?: emptyList()
                    if (calendars.isNullOrEmpty()) calendarError = "No calendars found."
                } catch (e: IOException) {
                    calendarError = "Network error fetching calendars: ${e.message?.take(100)}"
                    calendarList = emptyList()
                } catch (e: Exception) {
                    calendarError = "Error fetching calendars: ${e.message?.take(100)}"
                    calendarList = emptyList()
                } finally {
                    isLoadingCalendars = false
                }
            }
        } else {
            calendarList = emptyList()
            selectedCalendars.clear()
            todayEvents = emptyList()
            isLoadingCalendars = false
            isLoadingEvents = false
            calendarError = null
            eventsError = null
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        if (!hasCalendarPermission) {
            Text("Calendar permission is required.")
            Button(onClick = { calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR) }) {
                Text("Request Calendar Permission")
            }
        } else {
            googleSignInAccount?.let { account ->
                Text("Signed in as: ${account.displayName ?: account.email}", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { signOut() }) { Text("Sign Out") }
                Spacer(modifier = Modifier.height(8.dp))

                Text("Your Calendars:", style = MaterialTheme.typography.titleMedium)
                if (isLoadingCalendars) {
                    CircularProgressIndicator()
                } else if (calendarError != null) {
                    Text(text = calendarError!!, color = MaterialTheme.colorScheme.error)
                } else if (calendarList.isEmpty()) {
                    Text("No calendars available.")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(150.dp)) {
                        items(calendarList) { calendar ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Checkbox(
                                    checked = selectedCalendars[calendar.id] ?: false,
                                    onCheckedChange = { isChecked -> selectedCalendars[calendar.id] = isChecked }
                                )
                                Text(calendar.summary ?: "Unnamed Calendar", modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                Text("Today's Events Timeline:", style = MaterialTheme.typography.titleMedium)
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    TodayCalendarWidget(
                        events = todayEvents, // Pass the fetched events
                        modifier = Modifier.fillMaxSize()
                    )

                    if (isLoadingEvents) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else if (eventsError != null) {
                        Text(text = eventsError!!, modifier = Modifier.align(Alignment.Center).padding(16.dp), color = MaterialTheme.colorScheme.error)
                    } else if (todayEvents.isEmpty() && selectedCalendars.any { it.value }) {
                        Text("No events scheduled for today in selected calendars.", modifier = Modifier.align(Alignment.Center).padding(16.dp))
                    } else if (selectedCalendars.none { it.value } && calendarList.isNotEmpty()) {
                        Text("Select one or more calendars to view events.", modifier = Modifier.align(Alignment.Center).padding(16.dp))
                    }
                }
            } ?: run {
                if (isLoadingCalendars || isLoadingEvents) {
                    CircularProgressIndicator()
                } else if (calendarError != null) {
                     Text(calendarError!!, color = MaterialTheme.colorScheme.error)
                }
                else {
                    Text("Please sign in to access calendar data.")
                    Button(onClick = { signIn() }) { Text("Sign In with Google") }
                }
            }
        }
    }
}

private fun handleSignInResult(task: Task<GoogleSignInAccount>, onSuccess: (GoogleSignInAccount) -> Unit, onFailure: () -> Unit) {
    try {
        val account: GoogleSignInAccount = task.getResult(ApiException::class.java)
        if (account.grantedScopes.contains(Scope("https://www.googleapis.com/auth/calendar.readonly"))) {
            onSuccess(account)
        } else {
            onFailure()
        }
    } catch (e: ApiException) {
        onFailure()
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreviewNotSignedIn() {
    val context = LocalContext.current
    TodayCalendarWidgetTheme {
        MainScreen(dataStoreManager = PreferencesDataStoreManager(context))
    }
}

@Preview(showBackground = true, name = "MainScreen with Timeline Preview")
@Composable
fun MainScreenWithTimelinePreview() {
    val context = LocalContext.current
    val dataStoreManager = PreferencesDataStoreManager(context)
    TodayCalendarWidgetTheme {
        MainScreen(dataStoreManager = dataStoreManager)
    }
}

@Preview(showBackground = true, name = "Signed In - Mocked Data with Events")
@Composable
fun DefaultPreviewSignedInWithMockData() {
    val context = LocalContext.current
    val mockCalendarList = listOf(
        CalendarListEntry().setSummary("Personal").setId("personal1"),
        CalendarListEntry().setSummary("Work").setId("work1")
    )
    val mockSelectedCalendars = mutableStateMapOf("personal1" to true)
    val mockEvents = listOf(
        MyCalendarEvent(
            id = "p1", summary = "Preview Event 1 (09:00-10:30)",
            startTime = ZonedDateTime.now().withHour(9).withMinute(0),
            endTime = ZonedDateTime.now().withHour(10).withMinute(30),
            isAllDay = false
        ),
        MyCalendarEvent(
            id = "p2", summary = "Preview Event 2 (11:00-12:00)",
            startTime = ZonedDateTime.now().withHour(11).withMinute(0),
            endTime = ZonedDateTime.now().withHour(12).withMinute(0),
            isAllDay = false
        )
    )

    TodayCalendarWidgetTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Signed in as: Preview User", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { }) { Text("Sign Out") }
            Spacer(modifier = Modifier.height(8.dp))

            Text("Your Calendars:", style = MaterialTheme.typography.titleMedium)
            LazyColumn(modifier = Modifier.padding(8.dp).height(100.dp).fillMaxWidth()) {
                items(mockCalendarList) { calendar ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(
                            checked = mockSelectedCalendars[calendar.id] ?: false,
                            onCheckedChange = { /* mock */ }
                        )
                        Text(calendar.summary ?: "Unnamed Calendar", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            Text("Today's Events Timeline:", style = MaterialTheme.typography.titleMedium)
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                TodayCalendarWidget(
                    events = mockEvents, // Pass mock events
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
