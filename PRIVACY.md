# Privacy

This is the same text shown in the app (Settings > Privacy). It describes what the code does. If you publish the app, have it reviewed for your jurisdiction.

What stays on your phone
Everything. Speech recognition runs on this phone with no internet. Your audio, transcripts, vocabulary and settings are stored only in this app's private storage, which is excluded from Android backups. Nothing is uploaded, and there are no accounts, ads, analytics or crash reporters.

What uses the internet
Only model downloads, and only after you tap Download on the Models screen. They come from the public sherpa-onnx releases on GitHub. GitHub can see the request like any download. After the models are installed, captions work in airplane mode.

Audio storage
Off by default: audio is never written to storage. If you choose Temporary, audio is saved in a private folder while recording so an interrupted recording can be recovered or checked again, and it is deleted automatically once the transcript is saved. If you choose Keep, audio stays until you delete it. You can delete all saved audio or all transcripts in Settings at any time. Uninstalling the app removes everything.

Transcripts
Saved in the app's private database: the text, timestamps, language and model used. You can edit, rename, delete, copy, share or export them. Sharing and exporting send the text only to the app or file you choose.

System audio
When you choose System audio, Android shows its own screen-capture approval. This app uses it only to receive the sound that other apps play, and only from apps that allow capture. It does not record your screen. Android shows an indicator while capture is active.

Floating captions
The caption bar and the round CC bubble only draw over other apps. On their own they cannot read what is on other screens. You can drag the bubble anywhere; it rests against a screen edge.

Words on screen (optional, off by default, only in the "full" edition of the app)
If you turn on "Use words on screen" and enable Clearcap in Android's Accessibility settings, then while a recording is running the app reads the visible text of the app you are using, only to learn how names and technical terms are spelled. It skips password fields, text boxes you can type in, keyboards and system screens. It keeps a short list of unusual words in memory for that recording only, and discards it when the recording ends. Screen text is never saved, logged or sent anywhere. The service cannot tap, type or change anything. Turn it off in Settings, or in Android's Accessibility settings, at any time.

Permissions
Microphone: required for the microphone, and required by Android for system-audio capture. Notifications: shows the recording notification. Display over other apps: optional, for the floating caption bar and bubble. Accessibility service: optional, only for "Use words on screen". Internet: model downloads only.

Removed feature
Earlier versions could send a transcript to a cloud AI for notes. That feature has been removed. Notes already saved by it remain on your phone.
