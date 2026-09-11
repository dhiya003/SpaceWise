package com.verve.spacesweep;
import java.util.*;
public class FolderExclusionsTest {
    private static int count;
    static void check(boolean value){if(!value)throw new AssertionError("Test "+(count+1));count++;}
    public static void main(String[] args){
        Set<String> rules=new HashSet<>(Arrays.asList("DCIM/Camera","Documents/private"));
        check(FolderExclusions.matches("DCIM/Camera/",rules));
        check(FolderExclusions.matches("DCIM/Camera/trips/",rules));
        check(!FolderExclusions.matches("DCIM/CameraBackup/",rules));
        check(!FolderExclusions.matches("DCIM/",rules));
        check(FolderExclusions.documentPath("primary:Documents/private").equals("Documents/private"));
        check(FolderExclusions.documentPath("ABCD-1234:Documents/private").equals("Documents/private"));
        check(FolderExclusions.displayPath("Documents / primary:Documents/private/nested").equals("Documents/private/nested"));
        check(FolderExclusions.matches(FolderExclusions.displayPath("Documents / primary:Documents/private/nested"),rules));
        check(!FolderExclusions.matches(null,rules));
        check(!FolderExclusions.matches("DCIM/Camera",Collections.singleton("")));
        check(!FolderExclusions.matches("DCIM/Camera",Collections.emptySet()));
        System.out.println("All "+count+" folder exclusion tests passed.");
    }
}
