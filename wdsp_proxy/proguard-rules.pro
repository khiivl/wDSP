# SoundActivity is only ever launched by the system via the manifest's LAUNCHER intent-filter,
# never referenced from Java - the Android Gradle Plugin's default consumer rules already keep
# every manifest-declared component, so no custom keep rule is needed here. This file exists
# purely because build.gradle's proguardFiles requires it to be present on disk.
