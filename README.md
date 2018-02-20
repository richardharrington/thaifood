# thaifood

This app, when called, returns a json response to the user (I didn’t have time to make a proper front end) with information about the top 10 Thai restaurants in the city that currently display an “A” or a “B” in their window, sorted by most recent inspection date, with the ones on the same inspection date being sorted by inspection score (with a low score being the best).

Tragically, I lost both the actual code and the Heroku deployment. Yup.* The only thing that remains is the main select query, which I had saved in some notes.

So here is a writeup instead:

## thaifood postgresql db design

The `thaifood` data store uses postgresql for storage.

Because storing 400,000 rows in a Heroku postgreql db would cost money, the decision has been made to exclude all non-Thai-restaurant-related data during the ingest, which leaves ~5,000 inspection rows -- within Heroku’s free tier.

This system is designed to be able to re-ingest the information daily, as new information is posted to the Open Data site. Each day in the middle of the night, an ingest will be done.

There are two tables, `restaurant` and `inspection`. (In both of them, empty strings and the string “N/A”, which is extremely rare, are converted to `null` during ingest.)

`restaurant` contains the first 8 columns of data in each of the input csvs, all the information for identifying a restaurant. The primary key is the CAMIS column. All of the other 7 columns have been confirmed to always be the same for each CAMIS, so filtering is done during the ingest so that we only upload one row for each CAMIS.

We upload the data using an upsert (an `ON CONFLICT` clause), so that on subsequent ingests, if any of the information has changed for a particular CAMIS, it gets updated.

`inspection` contains the remaining columns of data from the input csv, which pertain to dates and results of inspections (plus the date the record was uploaded, in case we ever need that). In addition, we add the CAMIS column as a foreign key for the restaurant table.

Every row in the input csv produces a row in the `inspection` table. The primary key is a composite of the CAMIS id field, the inspection date, and the violation code. (In the main input data -- not in the Thai food, but you never know if it will happen one day -- there are a handful of cases of a null violation code. We could just ignore them, but one of them actually has a grade -- a `Z` pending grade -- which the user will want to know about. So if that rare case ever pops up in the Thai food rows, we assign a dummy violation code of `999`. The only alternative would be to set a new id for every record uploaded instead of using that composite primary key, but that would quickly cause the db to balloon in size).

Most of the reading from the Open Data site and processing the information is done using streams and lazy execution, but the actual uploading to the db needs to be done in batches. Right now we sidestep the difficult questions and just do it in one batch. If we were dealing with more data than we could hold in memory or comfortably send in a batch, we would divide it up into multiple batches for upload. I’m not sure if we would technically do that in one db transaction, but that would be ideal, so we could roll back all of our transactions if anything failed badly.

It says on the tooltip for the `score` field on the Open Data website that it is possible for the score to be adjudicated and replaced with a new score by a judge. I’m not sure if that means they add a new record. I have chosen to ignore this for the time being, but if it is true that they replace the information in old records, then the score should probably be included in the primary key, and updated if necessary on later ingests using an `ON CONFLICT` clause.

## The app in action

The app is written in Clojure, which I find to be a particularly convenient language for processing pipelines of data.

Upon startup (and in the middle of the night each day), it does an ingest of the latest csv from the Open Data website. Then it spins up a server which takes requests to do exactly one thing: return json for the top ten Thai food restaurants.

The json response consists of all the fields of restaurant info, plus its most recent inspection date with score and grade:

```json
{
  "status": 200,
  "body": {
    "description": "Top ten Thai restaurants in New York, sorted by date (descending) and score (ascending, low scores are better)",
    "data": {
      "camis": 3829322,
      "dba": "My Thai Place",
      "boro": "MANHATTAN",
      "building": "253",
      "street": "East 10th Street",
      "zipcode": "10009",
      "phone": "2125551212",
      "cuisine_description": "Thai",
      "inspection_date": "2018-02-14",
      "score": 5,
      "grade": "A"
    }
  }
}
```

Before we get to the query, here’s how restaurant grading is done in NYC, in a nutshell (there are many other miscellaneous kinds of inspections, but their grade fields are all `null`, so we can conveniently ignore them):

Inspection cycles (which occur for each restaurant randomly at some point during the year) begin with an initial inspection. If the restaurant gets less than 14 violation points, they get an A. If they have some insanely egregious violations, they get shut down immediately. If they are somewhere in between, they get to keep their previous grade up in the window (or they don’t have to put up a grade sign, if they are a new restaurant), and they get a few days to a month to clean up the place (theoretically, we could tell our friend when a restaurant has gotten a bad score and still has their A or B up, but let’s not bother with that). After the second inspection less than a month later (provided they don’t get shut down), they get their final grade, and they can put up either that or, if it’s a B or a C, a “pending” sign while they appeal.

In the case when a restaurant gets shut down, after it re-opens before it gets graded, it has a “P” in the grade column in the data feed. If it is pending an appeal of a grade, it has a “Z”.

What we want is the certainty that when our friend goes to the restaurant, they will see an “A” or a “B” in the window. As far as we can tell, that will definitely happen if: 1) The restaurant has an “A” or a “B” in the grade field, and 2) It does NOT have a “C”, a “P”, or a “Z” in the grade field on any date after its latest “A” or “B”.

So here’s what the query does:

From all the inspection rows with non-null grades, take the most recent row for each restaurant (identified by CAMIS id). Each inspection date may have many rows but will have only one grade (or a null grade), so from all those latest-grade rows, each cross-product of CAMIS and inspection date should correspond to one grade. If that grade is A or B for that restaurant, join that inspection info with the restaurant’s info using the CAMIS field, and we’re done!

(The `inspection` table, in addition to the aforementioned primary key, will also be indexed by inspection date.)

Here’s the query, with results using a dataset downloaded a week ago (which I actually saves in a note file!).

```sql
select
    top_i.inspection_date,
    top_i.score,
    top_i.grade,
    r.dba,
    r.boro,
    r.building,
    r.street,
    r.zipcode,
    r.phone,
    r.cuisine_description
from restaurant r
inner join
    -- Returns subset of inspection data for those inspection
    -- records where the restaurant received an A or a B
    -- (and that is also the most recent grade they've received)
    -- Note: all the scores and grades are the same for a given date, but
    -- I couldn't figure out a way to aggregate them other than using "min",
    -- which is not ideal because it masks a problem if my assumption is incorrect.
    -- TODO: some other aggregation method, that confirms that they're all the same.
    (select i.camis camis, i.inspection_date inspection_date, min(i.score) score, min(i.grade) grade
    from inspection i
    inner join
        -- Returns rows of camis and max_date,
        -- where max_date is the latest date
        -- for which that restaurant has any grade
        (select camis, max(inspection_date) max_date
        from inspection
        where grade is not null
        group by camis)
        max_date_table
    on i.camis = max_date_table.camis
    and i.inspection_date = max_date_table.max_date
    where i.grade in ('A', 'B')
    group by i.camis, i.inspection_date)
    top_i
on r.camis = top_i.camis
-- Show the top 10 recently inspected restaurants.
-- They will probably be on one day, so sort by lowest
-- score after date.
order by top_i.inspection_date desc, top_i.score asc
limit 10
;
```
```
+-------------------+-------------+-------------+---------------------------+-----------+------------+----------------+-----------+------------+-----------------------+
| inspection_date   | score       | grade       | dba                       | boro      | building   | street         | zipcode   | phone      | cuisine_description   |
|-------------------+-------------+-------------+---------------------------+-----------+------------+----------------+-----------+------------+-----------------------|
| 2018-02-05        | 4           | A           | ERAWAN THAI CUISINE       | QUEENS    | 4231       | BELL BOULEVARD | 11361     | 7184282112 | Thai                  |
| 2018-02-05        | 11          | A           | THAI ELEPHANT RESTAURANT  | QUEENS    | 2109       | 31 STREET      | 11105     | 7182048827 | Thai                  |
| 2018-02-01        | 12          | A           | PUTAWN LOCAL THAI KITCHEN | MANHATTAN | 1584       | 1ST AVE        | 10028     | 2129888800 | Thai                  |
| 2018-02-01        | 12          | A           | TALENT THAI KITCHEN       | MANHATTAN | 210        | EAST 34 STREET | 10016     | 2127258888 | Thai                  |
| 2018-01-31        | 8           | A           | SPICE                     | MANHATTAN | 39         | E 13TH ST      | 10003     | 2129823758 | Thai                  |
| 2018-01-31        | 12          | A           | THAI @ LEX                | MANHATTAN | 1244       | LEXINGTON AVE  | 10028     | 5622017865 | Thai                  |
| 2018-01-30        | 12          | A           | KHAO SARN                 | BROOKLYN  | 338        | BEDFORD AVE    | 11249     | 7189631238 | Thai                  |
| 2018-01-28        | 12          | A           | CAFE CHILI                | BROOKLYN  | 172        | COURT STREET   | 11201     | 7182600066 | Thai                  |
| 2018-01-26        | 3           | A           | POK POK NY                | BROOKLYN  | 117        | COLUMBIA ST    | 11231     | 7189239322 | Thai                  |
| 2018-01-25        | 11          | A           | 22 THAI CUISINE           | MANHATTAN | 59         | NASSAU ST      | 10038     | 2127329250 | Thai                  |
+-------------------+-------------+-------------+---------------------------+-----------+------------+----------------+-----------+------------+-----------------------+

```




---

* In case you’re wondering, here’s how it happened: I had deployed to Heroku and everything was fine, but I (extremely unwisely) left the uploading to Github as my last step, and Github had a problem with the giant test file that had been included in the first commit, a week ago. While wrestling in a sleep-deprived trance with the trickiness of the `git rebase` `--root` flag, I somehow ended up with a git history that consisted only of the first commit. Instead of restoring recent commits with `git reflog`, I decided (remember, sleep-deprived trance) to start with a clean git history by `rm`ing my `.git` folder and `git init`ing a new one, thus losing all the code except the few lines from the commit that was checked out -- the first commit. Conveniently, I also force-pushed to the Heroku remote, thus destroying the only backup of the recent git history, seconds before I realized what had happened. Kids, don’t let this happen to you.
